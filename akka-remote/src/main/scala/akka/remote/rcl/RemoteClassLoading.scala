package akka.remote.rcl

import akka.remote.netty.RemoteEncoder
import java.util.concurrent.ConcurrentMap
import com.google.common.base.Function
import akka.config.Config
import com.google.common.io.ByteStreams
import akka.remote.MessageSerializer
import akka.remote.protocol.RemoteProtocol._
import collection.JavaConversions._
import com.google.common.collect.{Lists, Multimaps, LinkedListMultimap, MapMaker}
import akka.remote.util.UUID_
import akka.event.EventHandler
import java.lang.{Long, ClassNotFoundException}
import java.util.{List, LinkedList}
import akka.remote.protocol.RemoteProtocol
import com.eaio.uuid.UUID
import org.jboss.netty.channel._
import com.google.protobuf.{ExtensionRegistry, ByteString}

/**
 * Implementation of the Remote Class Loading functionality.
 */
object RemoteClassLoading {


  //  val enabled = Config.config.getBoolean("akka.remote.remote-class-loading.enabled", true)
  val enabled = true

  // SENDER STUFF
  val endpointByCl: ConcurrentMap[ClassLoader, RemoteClassLoaderEndpoint] = new MapMaker().concurrencyLevel(4).weakKeys().makeComputingMap(new Function[ClassLoader, RemoteClassLoaderEndpoint] {
    def apply(cl: ClassLoader) = {
      val endpoint = new RemoteClassLoaderEndpoint(cl)
      endpointByUuid.put(endpoint.id, endpoint)
      endpoint
    }
  })

  val endpointByUuid: ConcurrentMap[UUID, RemoteClassLoaderEndpoint] = new MapMaker().concurrencyLevel(4).weakValues().makeMap[UUID, RemoteClassLoaderEndpoint]()

  // RECEIVER STUFF
  // todo how to expire this stuff?
  val rclsByUuid: ConcurrentMap[UUID, RemoteClassLoader] = new MapMaker().concurrencyLevel(4).makeComputingMap(new Function[UUID, RemoteClassLoader] {
    def apply(key: UUID) = new RemoteClassLoader(key)
  })

  // GLOBAL
  val classCache: ConcurrentMap[(UUID,String), Array[Byte]] = new MapMaker().concurrencyLevel(4).makeMap[(UUID, String), Array[Byte]]()
  val blacklisted: ConcurrentMap[(UUID,String), Boolean] = new MapMaker().concurrencyLevel(4).makeMap[(UUID, String), Boolean]()

}

class RetryWillBeAttemptedException extends RuntimeException

class RemoteClassLoadingSupport(val clientCl: Option[ClassLoader]) {

  // when RCL is happening any additional messages should wait until the rcl is completed
  val pendingRclClass = new ChannelLocal[(UUID, String)]
  val pendingMessages = new ChannelLocal[LinkedList[MessageEvent]] {
    override def initialValue(channel: Channel) = Lists.newLinkedList()
  }

  def handleBcreq(handlerContext: ChannelHandlerContext, event:MessageEvent, arp: AkkaRemoteProtocol){
    if(!arp.getInstruction.hasExtension(RemoteProtocol.bcreq)){
      EventHandler.warning(this, "Corrupt rcl request headers. Possible bug or you are doing something ungodly with the message.")
      return
    }
    val bcreq = arp.getInstruction.getExtension(RemoteProtocol.bcreq)

    val uuidProto = bcreq.getRclId
    val uuid = UUID_.toUuid(uuidProto)
    val fqn = bcreq.getFqn

    val endpoint = RemoteClassLoading.endpointByUuid.get(uuid)

    if (endpoint == null) {
      EventHandler.info(this, "ByteCodeRequest's enpoint is not available. Maybe the node got restarted?")
      replyWithBcresp(uuidProto, fqn, ByteCodeResponseCode.ENDPOINT_NOT_AVAILABLE, null, event)
      return
    }

    try {
      replyWithBcresp(uuidProto, fqn, ByteCodeResponseCode.OK, endpoint.getByteCode(fqn), event)
    } catch {
      case _ => {
        EventHandler.info(this, "Failed to find bytecode for (" + uuid + ", " + fqn)
        replyWithBcresp(uuidProto, fqn, ByteCodeResponseCode.BYTE_CODE_NOT_AVAILABLE, null, event)
      }
    }
  }

   def replyWithBcresp(uuid:UuidProtocol, fqn:String, code: ByteCodeResponseCode, bytecode: Array[Byte], event:MessageEvent){
    val arp = AkkaRemoteProtocol.newBuilder()
    arp.getInstructionBuilder.setCommandType(CommandType.BYTE_CODE_RESPONSE)
    val bcresp = ByteCodeResponseProtocol.newBuilder().setRclId(uuid).setFqn(fqn).setResponseCode(code)
    if(bytecode != null) bcresp.setBytecode(ByteString.copyFrom(bytecode))
    arp.getInstructionBuilder.setExtension(RemoteProtocol.bcresp , bcresp.build)
    event.getChannel.write(arp.build()) // todo retry 3 times then give up
  }


  def handleBcresp(ctx: ChannelHandlerContext, event:ChannelEvent, arp: AkkaRemoteProtocol, fun:(ChannelHandlerContext, MessageEvent) => Any){
    if (!arp.getInstruction.hasExtension(RemoteProtocol.bcresp)) {
      EventHandler.warning(this, "Corrupt rcl response headers. Possible bug or you are doing something ungodly with the message.")
      return
    }

    val bcresp = arp.getInstruction.getExtension(RemoteProtocol.bcresp)

    val uuidProto = bcresp.getRclId
    val uuid = UUID_.toUuid(uuidProto)
    val fqn = bcresp.getFqn

    val responseCode = bcresp.getResponseCode

    if (responseCode == ByteCodeResponseCode.OK){
      if (!bcresp.hasBytecode){
        EventHandler.error(this, "Bug in akka remote class loadig. Response code was OK but bytecode is not available.")
        return
      }
      RemoteClassLoading.classCache.put((uuid,fqn), bcresp.getBytecode.toByteArray)
    } else {
      // failed to get the bytecode lets blacklist this
      EventHandler.info(this, "Black listing (" + uuid + ", " + fqn +") since the response is " + responseCode.name())
      RemoteClassLoading.blacklisted.put((uuid,fqn), true)
    }

    // I don't think it is possible that we get a response for a different class than we requested but just in case
    if (!pendingRclClass.get(event.getChannel).equals((uuid, fqn))) {
      EventHandler.warning(this, "Possible bug in akka remote class loading. Received bytecode response for a different class than expected.")
      return
    }

    // we can _assume_ that no concurrent request will come to this channel until we are finished processing
    // todo verify this assumption

    pendingRclClass.remove(event.getChannel)

    val pending = pendingMessages.remove(event.getChannel) // todo make sure initialValue is read again after we remove this

    // just replay all the messages agains the parent handler it doesn't really matter what the response code was
    // todo maybe this can be improved but it gets pretty messy if we try to "fast copy" the remaining messages

    pending.foreach(fun(ctx,_))
  }


  def handleCnfe(rclId: UuidProtocol, fqn: String, event: MessageEvent) {
    val channel = event.getChannel
    pendingRclClass.set(channel, (UUID_.toUuid(rclId), fqn))
    pendingMessages.get(channel).addLast(event)
    val arp = AkkaRemoteProtocol.newBuilder()
    val builder = arp.getInstructionBuilder
    builder.setCommandType(CommandType.BYTE_CODE_REQUEST)
    builder.setExtension(RemoteProtocol.bcreq, ByteCodeRequestProtocol.newBuilder().setRclId(rclId).setFqn(fqn).build()).build()
    channel.write(arp.build()) // todo retry on error 3 times
    // todo add timer as we should give up after some time if we don't receive a reply
    throw new RetryWillBeAttemptedException
  }

  def handleMessage(ctx: ChannelHandlerContext, event: MessageEvent, fun:(ChannelHandlerContext, MessageEvent) => Any) {
     // if we are not waiting for bcresp then continue other-wize add to the list
    if(pendingRclClass.get(event.getChannel) == null){
       fun(ctx, event)
    }  else {
       pendingMessages.get(event.getChannel).add(event)
    }
  }


  def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent, fun:(ChannelHandlerContext, MessageEvent) => Any) {
      event.getMessage match {
        case arp: AkkaRemoteProtocol if arp.hasInstruction && arp.getInstruction.getCommandType == CommandType.BYTE_CODE_REQUEST => handleBcreq(ctx, event, arp)
        case arp: AkkaRemoteProtocol if arp.hasInstruction && arp.getInstruction.getCommandType == CommandType.BYTE_CODE_RESPONSE => handleBcresp(ctx, event, arp, fun)
        case arp: AkkaRemoteProtocol if arp.hasMessage => handleMessage(ctx, event, fun)
        case _ => fun(ctx,event)
      }
  }

  def getRclIdForInstance(instance: Any) = {
    val ref = MessageSerializer.box(instance)
    ref.getClass.getClassLoader match {
      case rcl:RemoteClassLoader => rcl._idCached
      case cl: ClassLoader => RemoteClassLoading.endpointByCl.get(cl)._idCached
      case null => null
    }
  }

  def getRclForDeserialization(rclId: UuidProtocol):ClassLoader = {
    val uuid = UUID_.toUuid(rclId)
    // try to get the endpoint first if it exists
    val uuid1 = RemoteClassLoading.endpointByUuid
    uuid1.get(uuid) match {
      case ep: RemoteClassLoaderEndpoint => ep.cl
      case _ => RemoteClassLoading.rclsByUuid.get(uuid)
    }
  }



  def deserialize(protocol: MessageProtocol, event:MessageEvent):Any = {
    println("Deserilaizeee")
     try {
       deserialize(protocol)
     } catch {
       case cnfe:ClassNotFoundException => handleCnfe(protocol.getRclId, cnfe.getMessage, event)
       case t:Throwable => throw t
     }
  }

  private def deserialize(protocol: MessageProtocol):Any = protocol.hasRclId match {
    case true => clientCl match {
      // if we have user set ClassLoader use that first other-wize use rcl only rcl CL
      case Some(cl) => MessageSerializer.deserialize(protocol, Option(new DualClassLoader(cl, getRclForDeserialization(protocol.getRclId))))
      case None => MessageSerializer.deserialize(protocol, Option(getRclForDeserialization(protocol.getRclId)))
    }
    case false => MessageSerializer.deserialize(protocol, clientCl)
  }

}

case class ByteCodeNotAvailableException(fqn: String, rcl: UUID) extends RuntimeException

/**
 * The sole purpose of this class is the provide Identity for the ClassLoader instance and Extract ByteCode from it.
 */
class RemoteClassLoaderEndpoint(val cl: ClassLoader) {

  val id = new UUID

  val _idCached = UUID_.toProto(id)

  def getByteCode(fqn: String): Array[Byte] = {
    // todo this will be improved with some elaborate hooks into more complex classlaoders such as WebSphere CL and similar
    try {
      ByteStreams.toByteArray(cl.getResourceAsStream(fqn.replace('.', '/') + ".class"))
    } catch {
      case _ => throw ByteCodeNotAvailableException(fqn, id)
    }
  }

}

object BlackListedClassException extends RuntimeException

class RemoteClassLoader(val id: UUID) extends ClassLoader(null) {

  val _idCached = UUID_.toProto(id)

  // we are delegation style class loader caching and stuff like synhronization is handled by loadClass method
  override def findClass(fqn: String): Class[_] = {
    // todo don't quite like the fact that class loader order happens here
    // we've already tried the System class loader
    // next try the classloader that loaded this class - the App CL
    try {
      return this.getClass.getClassLoader.loadClass(fqn)
    } catch {
      case _ => // silently ignore
    }
    // next try to use the Thread Context ClassLoader
    try {
      return Thread.currentThread().getContextClassLoader.loadClass(fqn)
    } catch {
      case _ => // silently ignore
    }

    // make sure this class is not blacklisted
    if (RemoteClassLoading.blacklisted.containsKey((id, fqn))) {
      EventHandler.info(this, "Class(" + id + ", " + fqn + ") is blacklisted remote class loading will not be retried.")
      throw BlackListedClassException
    }

    // next use the remote class loading to get the bytecode
    // we assume it is loaded in the classCache if it is not the called handle the CNFE specially and make it available
    RemoteClassLoading.classCache.get((id, fqn)) match {
      case bytecode: Array[Byte] => defineClass(fqn, bytecode, id)
      case _ => throw new ClassNotFoundException(fqn)
    }
  }


  def defineClass(fqn: String, bytecode: Array[Byte], rcl: UUID): Class[_] = {
    try {
      val clazz = defineClass(fqn, bytecode, 0, bytecode.length)
      // linking the class
      resolveClass(clazz)
      // we can safely remove the class from the classCache
      RemoteClassLoading.classCache.remove((rcl, fqn))
      clazz
    }
    catch {
      // define will call findClass (throwing CNFE) but the method will instead throw the NCDFE
      case ncdfe: NoClassDefFoundError => throw new ClassNotFoundException(ncdfe.getMessage.replace('/', '.'))
      case t: Throwable => throw t // we can get ClassFormatError or SecurityException or X
    }

  }

}

/**
 * First tries to get the stuff from A class loader if it fails it tries then from B
 */
class DualClassLoader(a:ClassLoader, b:ClassLoader) extends ClassLoader {
  override def findClass(fqn: String) = {
    try {
      a.loadClass(fqn)
    }
    catch {
      case _ => // silently ignore
    }
    try {
      b.loadClass(fqn)
    }
    catch {
      case _ => // silently ignore
    }

    throw new ClassNotFoundException(fqn)
  }
}



