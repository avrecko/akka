package akka.remote.netty.rcl

import akka.remote.netty.NettyRemoteTransport
import java.net.URL
import com.google.common.io.Resources

import akka.util.Timeout
import akka.util.duration._
import akka.remote.rcl.ThreadLocalReflectiveDynamicAccess
import org.fest.reflect.core.Reflection
import akka.actor._
import akka.pattern.ask
import akka.remote.RemoteProtocol.RemoteMessageProtocol
import com.google.protobuf.ByteString
import com.google.common.base.Charsets
import akka.remote.{RemoteProtocol, RemoteMessage, RemoteActorRefProvider}
import com.google.common.cache.{CacheLoader, CacheBuilder}
import com.typesafe.config.ConfigFactory
import akka.dispatch.Await
import java.io.File

class ActorfulRclTransport(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider) extends NettyRemoteTransport(_system, _provider) {

  val systemClassLoader = _system.dynamicAccess.classLoader

  // replace dynamic access with our thread local dynamic access
  val threadLocalDynamicAccess = new ThreadLocalReflectiveDynamicAccess(systemClassLoader)
  Reflection.field("_pm").ofType(classOf[DynamicAccess]).in(_system).set(threadLocalDynamicAccess)

  lazy val addressAsByteString = ByteString.copyFrom(address.toString, Charsets.UTF_8.name())

  val rclPort:Double = 11111 + (Math.random * 1000)

  val rclIsolatedSystem = {
    ActorSystem("RCL", ConfigFactory.parseString( """akka {
  actor {
    provider = "akka.remote.RemoteActorRefProvider"
  }
  remote {
    transport = "akka.remote.netty.NettyRemoteTransport"
    netty {
      hostname = "127.0.0.1"
      port = %s
    }
 }
}""" format rclPort).withFallback(ConfigFactory.parseFile(new File("/Users/avrecko/Projects/akka/akka-remote/src/multi-jvm/scala/akka.conf"))))
  }


  val remoteClassLoaders = CacheBuilder.newBuilder().build(new CacheLoader[ByteString, ActorfulRclClassLoader] {
    def load(key: ByteString) = {
      val utf = key.toStringUtf8
      println(utf)
      new ActorfulRclClassLoader(systemClassLoader, rclIsolatedSystem.actorFor(utf + "/user/RemoteClassLoading"), key)
    }
  })

  val rclActor = rclIsolatedSystem.actorOf(Props {
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
          case cl: ClassLoader if !name.startsWith("java.") && !name.startsWith("scala.") ⇒ RclMetadata.addOrigin(pb, ByteString.copyFrom("akka://RCL@127.0.0.1:" + rclPort.asInstanceOf[Int], Charsets.UTF_8.name()))
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

class ActorfulRclClassLoader(parent: ClassLoader, origin: ActorRef, val originAddress: ByteString) extends ClassLoader(parent) {

  implicit val timeout = Timeout(100 seconds)


  // normally this is dangerous as findClass is synchronized but we are safe as the
  // rcl mechanism internally doesn't require to touch remote classloaders to operate itself
  override def findClass(fqn: String): Class[_] = {
    println("Looking for " + fqn)
    println("Remote actor is " + origin.getClass)
    println("Remote actor is " + origin.path.address)
    // lets ask the origin for this class
    val future = origin ? DoYouHaveThisClass(fqn)

    Await.result(future, timeout.duration)  match {
      case  YesIHaveThisClass(sender, fqn, bytecode)  =>{
        println("Got result " + fqn)
         defineClass(fqn, bytecode, 0, bytecode.length)
      }

      case _ =>  throw new ClassNotFoundException(fqn)
    }
  }
}

class RclActor(val urlishClassLoader: ClassLoader) extends Actor {

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
    case _ ⇒ // just drop it
  }
}

case class DoYouHaveThisClass(fqn: String)

case class YesIHaveThisClass(sender: ActorRef, fqn: String, bytecode: Array[Byte])

case class NoIDontHaveThisClass(sender: ActorRef, fqn: String)

