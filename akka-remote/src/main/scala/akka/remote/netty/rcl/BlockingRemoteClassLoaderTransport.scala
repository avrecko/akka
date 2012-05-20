package akka.remote.netty.rcl

import akka.remote.netty.NettyRemoteTransport
import akka.remote.rcl.ThreadLocalReflectiveDynamicAccess
import org.fest.reflect.core.Reflection
import com.typesafe.config.ConfigFactory
import akka.remote.RemoteProtocol.RemoteMessageProtocol
import com.google.protobuf.ByteString
import akka.remote.{ RemoteMessage, RemoteProtocol, RemoteActorRefProvider }
import akka.dispatch.Await
import akka.actor._
import java.net.URL
import com.google.common.io.Resources

import akka.util.Timeout
import akka.util.duration._
import akka.pattern.ask
import com.google.common.base.Charsets
import com.google.common.cache.{ LoadingCache, CacheLoader, CacheBuilder }
import util.Random
import java.util.ArrayList
import com.google.common.collect.Iterables

class BlockingRemoteClassLoaderTransport(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider) extends NettyRemoteTransport(_system, _provider) {

  val systemClassLoader = _system.dynamicAccess.classLoader

  val systemClassLoaderChain: Array[ClassLoader] = {
    val clChain = new ArrayList[ClassLoader]()
    var currentCl = systemClassLoader
    while (currentCl != null) {
      clChain.add(currentCl)
      currentCl = currentCl.getParent
    }
    Iterables.toArray(clChain, classOf[ClassLoader])
  }

  // replace dynamic access with our thread local dynamic access
  val threadLocalDynamicAccess = new ThreadLocalReflectiveDynamicAccess(systemClassLoader)
  Reflection.field("_pm").ofType(classOf[DynamicAccess]).in(_system).set(threadLocalDynamicAccess)

  // boot up isolated system to do RCL communication (updates etc.)
  val rclPort = new Random().nextInt(10000) + 20000
  val hostname = _system.settings.config.getString("akka.remote.netty.hostname")

  val rclCommunicationSystem = RclConfig.newIsolatedSystem(hostname, rclPort, systemClassLoader)
  // install the RCL actor to respond to class file requests
  val rclActor = rclCommunicationSystem.actorOf(Props {
    new RclActor(systemClassLoader)
  }, "rcl")

  val originAddressByteString = ByteString.copyFrom("akka://rcl@" + hostname + ":" + rclPort, Charsets.UTF_8.name())

  val remoteClassLoaders: LoadingCache[ByteString, RclBlockingClassLoader] = CacheBuilder.newBuilder().build(new CacheLoader[ByteString, RclBlockingClassLoader] {
    def load(address: ByteString) = {
      val originRclActor = address.toStringUtf8 + "/user/rcl"
      new RclBlockingClassLoader(systemClassLoader, rclCommunicationSystem.actorFor(originRclActor), address)
    }
  })

  // just make sure the "context" has the correct classloader set
  override def receiveMessage(remoteMessage: RemoteMessage) {
    RclMetadata.getOrigin(remoteMessage) match {
      case someOrigin: ByteString if someOrigin.equals(originAddressByteString) ⇒ super.receiveMessage(remoteMessage)
      case someOrigin: ByteString ⇒ {
        try {
          // the RCL CL cannot be null, but can point to downed system
          threadLocalDynamicAccess.setThreadLocalClassLoader(remoteClassLoaders.get(someOrigin))
          super.receiveMessage(remoteMessage)
        } finally {
          threadLocalDynamicAccess.setThreadLocalClassLoader(systemClassLoader)
        }
      }
      case _ ⇒ super.receiveMessage(remoteMessage)
    }
  }

  // just tag the message
  override def createRemoteMessageProtocolBuilder(recipient: ActorRef, message: Any, senderOption: Option[ActorRef]) = {
    val pb = super.createRemoteMessageProtocolBuilder(recipient, message, senderOption)

    message match {
      case ref: AnyRef ⇒ {
        ref.getClass.getClassLoader match {
          case rcl: RclBlockingClassLoader ⇒ RclMetadata.addOrigin(pb, rcl.originAddress)
          case cl: ClassLoader ⇒ {
            if (systemClassLoaderChain.find(_ == cl).isDefined) {
              RclMetadata.addOrigin(pb, originAddressByteString)
            }
          }
          case null ⇒ // do nothing
        }
      }
      case _ ⇒ // do nothing
    }
    pb
  }

  override def shutdown() {
    // if we are shutting down the transports lets shut down the isolated rcl system too
    rclCommunicationSystem.shutdown()
    super.shutdown()
  }
}

class RclBlockingClassLoader(parent: ClassLoader, origin: ActorRef, val originAddress: ByteString) extends ClassLoader(parent) {

  implicit val timeout = Timeout(19 seconds)

  // normally it is not possible to block in here as this will in fact block the netty dispatcher i.e. no new stuff on this channel
  // but we are using a secondary actor system just for RCL so it is safe to block
  override def findClass(fqn: String): Class[_] = {
    Await.result(origin ? DoYouHaveThisClass(fqn), timeout.duration) match {
      case YesIHaveThisClass(fqn, bytecode) ⇒ defineClass(fqn, bytecode, 0, bytecode.length)
      case _                                ⇒ throw new ClassNotFoundException(fqn)
    }
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
      case _       ⇒ null
    }
  }
}

object RclConfig {

  def newIsolatedSystem(hostname: String, port: Int, classLoader: ClassLoader) = {

    val config = ConfigFactory.parseString("""
      akka {
         actor {
            provider = "akka.remote.RemoteActorRefProvider"
         }
         remote {
           transport = "akka.remote.netty.NettyRemoteTransport"
           untrusted-mode = off
           remote-daemon-ack-timeout = 30s
           log-received-messages = on
           log-sent-messages = on
           netty {
             backoff-timeout = 0ms
             secure-cookie = ""
             require-cookie = off
             use-passive-connections = on
             use-dispatcher-for-io = ""
             hostname = "%s"
             port = %s
             outbound-local-address = "auto"
             message-frame-size = 1 MiB
             connection-timeout = 120s
             backlog = 4096
             execution-pool-keepalive = 60s
             execution-pool-size = 4
             max-channel-memory-size = 0b
             max-total-memory-size = 0b
             reconnect-delay = 5s
             read-timeout = 0s
             write-timeout = 10s
             all-timeout = 0s
             reconnection-time-window = 600s
           }
         }
      }""" format (hostname, port))
    ActorSystem("rcl", config, classLoader)
  }
}

class RclActor(val urlishClassLoader: ClassLoader) extends Actor {

  def receive = {
    case DoYouHaveThisClass(fqn) ⇒ {
      val resourceName = fqn.replaceAll("\\.", "/") + ".class"
      urlishClassLoader.getResource(resourceName) match {
        case url: URL ⇒ sender ! {
          sender ! YesIHaveThisClass(fqn, Resources.toByteArray(url))
        }
        case _ ⇒ sender ! NoIDontHaveThisClass(fqn)
      }
    }
    case _ ⇒ // just drop it
  }
}

case class DoYouHaveThisClass(fqn: String)

case class YesIHaveThisClass(fqn: String, bytecode: Array[Byte])

case class NoIDontHaveThisClass(fqn: String)
