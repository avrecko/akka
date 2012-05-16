package akka.remote.netty.rcl

import akka.remote.netty.NettyRemoteTransport
import java.net.URL
import com.google.common.io.Resources

import akka.util.Timeout
import akka.util.duration._
import akka.remote.rcl.ThreadLocalReflectiveDynamicAccess
import org.fest.reflect.core.Reflection
import akka.actor._
import akka.remote.RemoteProtocol.RemoteMessageProtocol
import com.google.protobuf.ByteString
import com.google.common.base.Charsets
import akka.remote.{RemoteProtocol, RemoteMessage, RemoteActorRefProvider}
import com.google.common.cache.{CacheLoader, CacheBuilder}

class ActorfulRclTransport(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider) extends NettyRemoteTransport(_system, _provider) {

  val systemClassLoader = _system.dynamicAccess.classLoader

  // replace dynamic access with our thread local dynamic access
  val threadLocalDynamicAccess = new ThreadLocalReflectiveDynamicAccess(systemClassLoader)
  Reflection.field("_pm").ofType(classOf[DynamicAccess]).in(_system).set(threadLocalDynamicAccess)

  lazy val addressAsByteString = ByteString.copyFrom(address.toString, Charsets.UTF_8.name())

  val remoteClassLoaders = CacheBuilder.newBuilder().build(new CacheLoader[ByteString, ActorfulRclClassLoader] {
    def load(key: ByteString) = new ActorfulRclClassLoader(systemClassLoader, _system.actorFor(key.toStringUtf8 + "/user/RemoteClassLoading"), rclActor, key)
  })

  // register our classloader origin actor
  // todo I don't like that is registered under /user/RemoteClassLoading
  // ideally we'd have /rcl or I can target /remote and intercept it before remote daemon gets it
  // need some advice
  val rclActor = _system.actorOf(Props {
    new RclActor(systemClassLoader)
  }, "RemoteClassLoading")

  override def receiveMessage(remoteMessage: RemoteMessage) {
    println("recevied msg " + remoteMessage.sender)
    RclMetadata.getOrigin(remoteMessage) match {
      case origin: ByteString ⇒ {
        try {
          // get the remote class loader for this class
          threadLocalDynamicAccess.setThreadLocalClassLoader(remoteClassLoaders.get(origin))
          super.receiveMessage(remoteMessage)
        } finally {
          threadLocalDynamicAccess.removeThreadLocalClassLoader()
        }
      }
      case _ ⇒ super.receiveMessage(remoteMessage)
    }
  }

  override def createRemoteMessageProtocolBuilder(recipient: ActorRef, message: Any, senderOption: Option[ActorRef]) = {
    val pb = super.createRemoteMessageProtocolBuilder(recipient, message, senderOption)
    message match {
      case ref: AnyRef ⇒ {
        val name = ref.getClass.getCanonicalName
        ref.getClass.getClassLoader match {
          case rcl: ActorfulRclClassLoader ⇒ RclMetadata.addOrigin(pb, rcl.originAddress)
          case cl: ClassLoader if !name.startsWith("java.") && !name.startsWith("scala.") ⇒ RclMetadata.addOrigin(pb, addressAsByteString)
          case _ ⇒ // don't tag
        }
      }
      case _ ⇒ // don't tag
    }
    pb
  }
}

object RclMetadata {

  val INPUT_FIELD = Reflection.field("input").ofType(classOf[RemoteProtocol.RemoteMessageProtocol]);

  def addOrigin(pb: RemoteMessageProtocol.Builder, origin: ByteString) {
    val metadataBuilder = pb.addMetadataBuilder()
    metadataBuilder.setKey("origin")
    metadataBuilder.setValue(origin)
  }

  def getOrigin(rm: RemoteMessage): ByteString = {
    val rmp = INPUT_FIELD.in(rm).get()
    import scala.collection.JavaConversions._
    rmp.getMetadataList.find(_.getKey.equals("origin")) match {
      case Some(e) ⇒ e.getValue
      case _ ⇒ null
    }
  }
}

class ActorfulRclClassLoader(parent: ClassLoader, origin: ActorRef, self: ActorRef, val originAddress: ByteString) extends ClassLoader(parent) {

  implicit val timeout = Timeout(100 seconds)


  override def loadClass(name: String, resolve: Boolean): Class[_] = {
    var c = findLoadedClass(name);
    if (c == null) {
      try {
        if (parent != null) {
          c = parent.loadClass(name);
        }
      } catch {
        case e: ClassNotFoundException => c = findClass(name);
      }
    }
    if (resolve) {
      resolveClass(c);
    }
    c;
  }

  // normally this is dangerous as findClass is synchronized but we are safe as the
  // rcl mechanism internally doesn't require to touch remote classloaders to operate itself
  override def findClass(fqn: String): Class[_] = {
    println("Looking for " + fqn)
    println("Remote actor is " + origin.getClass)
    println("Remote actor is " + origin.path.address)
    // lets ask the origin for this class
    origin.tell(DoYouHaveThisClass(fqn), self)
    Thread.sleep(10000000)
    throw new ClassNotFoundException(fqn)
  }
}

class RclActor(urlishClassLoader: ClassLoader) extends Actor {

  def receive = {
    case DoYouHaveThisClass(fqn) ⇒ {
      println("Asked about " + fqn)
      val resourceName = fqn.replaceAll("\\.", "/") + ".class"
      urlishClassLoader.getResource(resourceName) match {
        case url: URL ⇒ sender ! {
          val array = Resources.toByteArray(url)
          sender ! YesIHaveThisClass(self, fqn, array)
          println("Replied with  " + fqn + " size of " + array.length)
        }
        case _ ⇒ sender ! NoIDontHaveThisClass(self, fqn)
      }
    }
    case YesIHaveThisClass(sender, fqn, bytecode) => {
      println("REceived YES!!!")
    }
    case NoIDontHaveThisClass(sender, fqn) => {
      println("REceived NO!!!")
    }
    case _ ⇒ // just drop it
  }
}

case class DoYouHaveThisClass(fqn: String)

case class YesIHaveThisClass(sender: ActorRef, fqn: String, bytecode: Array[Byte])

case class NoIDontHaveThisClass(sender: ActorRef, fqn: String)