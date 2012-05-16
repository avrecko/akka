package akka.remote.netty.rcl

import akka.remote.netty.NettyRemoteTransport
import java.net.{ URL, URLClassLoader }
import java.io.File
import com.google.common.io.{ ByteStreams, Files }
import akka.remote.rcl.FileCompressionUtil._
import akka.remote.rcl.URLishClassLoaderUtil._
import akka.remote.rcl.ThreadLocalReflectiveDynamicAccess
import org.fest.reflect.core.Reflection
import com.google.protobuf.ByteString
import com.google.common.base.Charsets
import akka.actor._
import java.util.UUID
import java.util.concurrent.{ TimeUnit, CopyOnWriteArraySet, Semaphore, ConcurrentHashMap }
import akka.remote.{ RemoteProtocol, RemoteActorRef, RemoteMessage, RemoteActorRefProvider }
import akka.remote.RemoteProtocol.RemoteMessageProtocol

/**
 * Created with IntelliJ IDEA.
 * User: avrecko
 * Date: 5/15/12
 * Time: 9:14 PM
 * To change this template use File | Settings | File Templates.
 */

class NettyRclRemoteTransport(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider) extends NettyRemoteTransport(_system, _provider) {

  val systemClassLoader = _system.dynamicAccess.classLoader

  // lets do a snapshot of the "whole" classpath
  // we can eagerly do this as there is no point in using this protocol if we are not going to connect to remote clients
  val classPathJar = createJar(filterClassPath(findClassPathFor(systemClassLoader)))

  // replace dynamic access with our thread local dynamic access
  val threadLocalDynamicAccess = new ThreadLocalReflectiveDynamicAccess(systemClassLoader)
  Reflection.field("_pm").ofType(classOf[DynamicAccess]).in(_system).set(threadLocalDynamicAccess)

  val remoteClassLoaders = new ConcurrentHashMap[ByteString, ClassLoader]()
  val originOfRemoteClassLoaders = new ConcurrentHashMap[ClassLoader, ByteString]()

  val forwardSyncSemaphores = new ConcurrentHashMap[Address, Semaphore]()
  val forwardSyncedClients = new CopyOnWriteArraySet[Address]
  val forwardSyncLock = new Object

  lazy val addressByteString = ByteString.copyFrom(address.toString, Charsets.UTF_8.name())

  val dummyActor = _system.actorOf(Props {
    new DummyActor(this)
  }, "RCL-ACK-WAIT-ACTOR-" + UUID.randomUUID().toString)

  override def send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef) {
    val recipientAddress = recipient.path.address
    if (forwardSyncedClients.contains(recipientAddress)) {
      // we have already talked to this client so its we can just forward the message
      super.send(message, senderOption, recipient)
    } else {
      // stop the world we have not yet talked to this client
      // lets do a forward sync
      forwardSyncLock.synchronized {
        // double checked locking to prevent race condition
        if (forwardSyncedClients.contains(recipientAddress)) {
          super.send(message, senderOption, recipient)
        } else {
          // do the whole forward sync
          doForwardSync(message, senderOption, recipient, recipientAddress)
        }
      }
    }
    log.debug("RCL SEND EXITED")
  }

  def doForwardSync(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef, recipientAddress: Address) {
    // first send our whole classpath to the other guy
    // we will block until we receive the classpath ack
    // it is the only way to guarantee correctness
    log.debug("Sending whole classpath from {} to {}.", address, recipientAddress)
    val semaphore = new Semaphore(0)

    forwardSyncSemaphores.put(recipientAddress, semaphore)

    super.send(new ClassPathMsg(dummyActor, Files.toByteArray(new File(classPathJar.getName))), None, recipient)

    val success = semaphore.tryAcquire(5, TimeUnit.MINUTES)
    // todo what is the best course of action? we've failed to do the RCL sync
    if (!success) {
      log.debug("Rcl Sync failed from {} to {}.", address, recipientAddress)
      throw new RuntimeException("Failed to forward sync the classpath with client " + recipientAddress)
    }

    // mark that we have successfully done the forward sync
    log.debug("Rcl Sync successful from {} to {}.", address, recipientAddress)
    forwardSyncedClients.add(recipientAddress)

    // send the original message
    // note this is still inside the sync all is well
    super.send(message, senderOption, recipient)
  }

  override def receiveMessage(remoteMessage: RemoteMessage) {
    val metadata = new RclMetadataReader(remoteMessage)
    if (metadata.isClassPathMsg() || metadata.isClassPathAckMsg()) {
      // we can be sure this is safe to deserialize
      remoteMessage.payload match {
        case cp: ClassPathMsg ⇒ {
          // put the new classpath in the cache
          val sender = cp.classpathSender.path.address
          log.debug("Recevied ClassPath from {}", sender)
          val newRclClassLoader = new RclClassLoader(systemClassLoader, cp.jarContent)
          val senderByteString = ByteString.copyFrom(sender.toString, Charsets.UTF_8.name())
          remoteClassLoaders.put(senderByteString, newRclClassLoader)
          originOfRemoteClassLoaders.put(newRclClassLoader, senderByteString)
          super.send(ClassPathAckMsg(address, cp.classpathSender), None, cp.classpathSender.asInstanceOf[RemoteActorRef])
        }
        case ack: ClassPathAckMsg ⇒ {
          // we will eat the message by not allowing it to continue
          log.debug("Received ACK from {} intended for {}.", ack.ackingClient, ack.classpathSender.path.address)
          val semaphore = forwardSyncSemaphores.get(ack.ackingClient)
          if (semaphore != null) semaphore.release();
        }
        case _ ⇒ {
          log.error("Bug in receiveMessage.")
        }
      }
    } else if (metadata.hasOrigin()) {
      // we must make sure to use to use the correct classloader in the context
      val origin = metadata.getOrigin()
      val classLoaderToUse = if (addressByteString.equals(origin)) {
        systemClassLoader
      } else {
        remoteClassLoaders.get(origin)
      }

      threadLocalDynamicAccess.setThreadLocalClassLoader(classLoaderToUse)
      // now it is safe to proceed
      super.receiveMessage(remoteMessage)
    } else {
      // must be safe to deserialize since it is not tagged with RCL origin
      // unless the client forgot to use RCL transport ;)
      super.receiveMessage(remoteMessage)
    }

  }

  override def createRemoteMessageProtocolBuilder(recipient: ActorRef, message: Any, senderOption: Option[ActorRef]) = {
    val rpb = super.createRemoteMessageProtocolBuilder(recipient, message, senderOption)

    // handle ClassPath msg
    message match {
      case a: ClassPathMsg    ⇒ new RclMetadataWriter(rpb).addClassPathMsgMeta
      case b: ClassPathAckMsg ⇒ new RclMetadataWriter(rpb).addClassPathAckMsgMeta
      case c: AnyRef ⇒ {
        val loader = c.getClass.getClassLoader
        // we are guaranteed if this class was not loaded by _this_ tranpost then we must know about the remote classloader that loaded it
        // todo: not really true as I can use a classloader not known to the system will revise
        val origin = if (loader == systemClassLoader) addressByteString else originOfRemoteClassLoaders.get(loader)
        // tag it with classloader origin
        new RclMetadataWriter(rpb).addOriginMeta(origin)
      }
      case _ ⇒ // let it continue
    }
    rpb
  }
}

// CHAT PROTOCOL STUFF
trait RclSyncMsg extends Serializable

case class ClassPathMsg(classpathSender: ActorRef, jarContent: Array[Byte]) extends RclSyncMsg

case class ClassPathAckMsg(ackingClient: Address, classpathSender: ActorRef) extends RclSyncMsg

// PROTOBUF UTILS

object RclMetadataReader {
  val INPUT_FIELD = Reflection.field("input").ofType(classOf[RemoteProtocol.RemoteMessageProtocol]);
}

class RclMetadataReader(rm: RemoteMessage) {

  // a bit annoying to get the field this way
  val input = RclMetadataReader.INPUT_FIELD.in(rm).get()

  import scala.collection.JavaConversions._

  def isClassPathMsg(): Boolean = input.getMetadataList.find(_.getKey.equals("classpathMsg")).isDefined

  def isClassPathAckMsg(): Boolean = input.getMetadataList.find(_.getKey.equals("classpathAckMsg")).isDefined

  def hasOrigin(): Boolean = input.getMetadataList.find(_.getKey.equals("origin")).isDefined

  def getOrigin(): ByteString = input.getMetadataList.find(_.getKey.equals("origin")).get.getValue
}

class RclMetadataWriter(rmb: RemoteMessageProtocol.Builder) {

  def addClassPathMsgMeta {
    val metadataBuilder = rmb.addMetadataBuilder()
    metadataBuilder.setKey("classpathMsg")
    metadataBuilder.setValue(ByteString.EMPTY)
  }

  def addClassPathAckMsgMeta {
    val metadataBuilder = rmb.addMetadataBuilder()
    metadataBuilder.setKey("classpathAckMsg")
    metadataBuilder.setValue(ByteString.EMPTY)
  }

  def addOriginMeta(origin: ByteString) {
    val metadataBuilder = rmb.addMetadataBuilder()
    metadataBuilder.setKey("origin")
    metadataBuilder.setValue(origin)
  }
}

// DUMMY ACTOR NEEDED FOR TARGETING THE REMOTE SYSTEM
class DummyActor(rp: NettyRclRemoteTransport) extends Actor {
  protected def receive = {
    case _ ⇒ rp.log.error("Dummy actor should not receive any messages. Bug!")
  }
}

// REMOTE CLASS LOADER
class RclClassLoader(parent: ClassLoader, classPath: Array[Byte]) extends URLClassLoader(new Array[URL](0), parent) {
  val jar = new File(Files.createTempDir(), "rcled.jar");
  Files.copy(ByteStreams.newInputStreamSupplier(classPath), jar)
  // URL classloader can handle jar files
  addURL(new URL("file://" + jar.getPath()));
}