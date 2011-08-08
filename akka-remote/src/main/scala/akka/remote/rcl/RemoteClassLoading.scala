package akka.remote.rcl

import com.google.protobuf.ByteString
import akka.remote.netty.RemoteEncoder
import java.util.concurrent.ConcurrentMap
import com.google.common.base.Function
import akka.config.Config
import com.google.common.io.ByteStreams
import akka.remote.MessageSerializer
import akka.remote.protocol.RemoteProtocol._
import com.eaio.uuid.UUID
import collection.JavaConversions._
import java.lang.ClassNotFoundException
import org.jboss.netty.channel._
import java.util.LinkedList
import com.google.common.collect.{Lists, Multimaps, LinkedListMultimap, MapMaker}

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
  val classCache: ConcurrentMap[(String, UUID), Array[Byte]] = new MapMaker().concurrencyLevel(4).makeMap[(String, UUID), Array[Byte]]()

  // UTILITIES
  def fromUuidProto(uuid: UuidProtocol) = new UUID(uuid.getHigh, uuid.getLow)

  def toUuidProto(uuid: UUID) = UuidProtocol.newBuilder().setHigh(uuid.getTime).setLow(uuid.getClockSeqAndNode).build()

  def getClassLoaderForDeserialization(cl: Option[ClassLoader], mp: MessageProtocol): Option[ClassLoader] = {
    if (!mp.hasRclId) {
      cl
    }
    if (RemoteClassLoading.enabled) {
      val uuid = RemoteClassLoading.fromUuidProto(mp.getRclId)
      val endpoint = RemoteClassLoading.endpointByUuid.get(uuid)
      val rcl = if (endpoint != null) endpoint.cl else  RemoteClassLoading.rclsByUuid.get(uuid)
      if (cl != None) {
        Option(new DualParentClassLoader(cl.get, rcl))
      } else {
        Option(rcl)
      }
    } else {
      cl
    }
  }

}

case class ByteCodeNotAvailableException(fqn:String, rcl:UUID) extends RuntimeException

/**
 * The sole purpose of this class is the provide Identity for the ClassLoader instance and Extract ByteCode from it.
 */
class RemoteClassLoaderEndpoint(val cl: ClassLoader) {

  val id = new UUID

  val _idCached = RemoteClassLoading.toUuidProto(id)

  def getByteCode(fqn: String): Array[Byte] = {
    // todo this will be improved with some elaborate hooks into more complex classlaoders such as WebSphere CL and similar
    try {
      ByteStreams.toByteArray(cl.getResourceAsStream(fqn.replace('.', '/') + ".class"))
    } catch {
      case _ => throw ByteCodeNotAvailableException(fqn, id)
    }
  }

}

class RemoteClassLoader(val id: UUID) extends ClassLoader(null) {

  val _idCached = RemoteClassLoading.toUuidProto(id)

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

    // next use the remote class loading to get the bytecode
    // we assume it is loaded in the classCache if it is not the called handle the CNFE specially and make it available
    RemoteClassLoading.classCache.get((fqn, id)) match {
      case bytecode: Array[Byte] => defineClass(fqn, bytecode, id)
      case _ => throw new ClassNotFoundException(fqn)
    }
  }


  def defineClass(fqn: String, bytecode: Array[Byte], rcl:UUID): Class[_] = {
    try {
      val clazz = defineClass(fqn, bytecode, 0, bytecode.length)
      // linking the class
      resolveClass(clazz)
      // we can safely remove the class from the classCache
      RemoteClassLoading.classCache.remove((fqn,rcl))
      clazz
    }
    catch {
      // define will call findClass (throwing CNFE) but the method will instead throw the NCDFE
      case ncdfe:NoClassDefFoundError => throw new ClassNotFoundException(ncdfe.getMessage.replace('/','.'))
      case t:Throwable => throw t // we can get ClassFormatError or SecurityException or X
    }

  }

}

//todo figure a better way if possible
class DualParentClassLoader(val p1: ClassLoader, val p2: ClassLoader) extends ClassLoader {

  override def findClass(fqn: String): Class[_] = {
    try {
      return p1.loadClass(fqn)
    }
    catch {
      case _ => // silently ignore
    }

    try {
      return p2.loadClass(fqn)
    }
    catch {
      case _ => // silently ignore
    }

    throw new ClassNotFoundException(fqn)
  }
}

/**
 * Installed just before the RemoteServer/ActiveRemoteClient and handles the loading of classes.
 */
class RemoteClassLoadingUpStreamHandler(val name: String) extends SimpleChannelUpstreamHandler {

  val pendingRclClass = new ChannelLocal[(String, UUID)]
  val pendingMessages = new ChannelLocal[LinkedList[MessageEvent]]{
    override def initialValue(channel: Channel) = Lists.newLinkedList()
  }

  override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    // todo expire rcl cache on channel close
    super.channelOpen(ctx, e)
  }

  def handleBcreq(bcreq: ByteCodeRequestProtocol, ctx: ChannelHandlerContext, e: MessageEvent): Unit = {
    val uuidProto = bcreq.getRclId
    val uuid = RemoteClassLoading.fromUuidProto(bcreq.getRclId)
    val endpoint = RemoteClassLoading.endpointByUuid.get(uuid)

    val fqn = new String(bcreq.getFqn.toByteArray)

    // just encode the bytecode
    val bcrep = ByteCodeResponseProtocol.newBuilder().setFqn(bcreq.getFqn).setRequestingRclId(uuidProto)
    try {
      bcrep.setBytecode(ByteString.copyFrom(endpoint.getByteCode(fqn)))
    } catch {
      case _ => // silently ignore
    }

    e.getChannel.write(RemoteEncoder.encode(bcrep.build())).awaitUninterruptibly()
    // todo should I wait uninterruptably?
  }

  def handleBcresp(bcresp: ByteCodeResponseProtocol, ctx: ChannelHandlerContext, e: MessageEvent): Unit = {
    val fqn = new String(bcresp.getFqn.toByteArray)
    val uuidProto = bcresp.getRequestingRclId
    val uuid = RemoteClassLoading.fromUuidProto(bcresp.getRequestingRclId)
    val bytecode = bcresp.getBytecode.toByteArray

    RemoteClassLoading.classCache.put((fqn, uuid), bytecode)

    // maybe unnecessary check?
    if (pendingRclClass.get(e.getChannel) != (fqn -> uuid) ){
      println("Received soemthng not expected")
      return
    }

    pendingRclClass.remove(e.getChannel)
    val pendingMessagsList = pendingMessages.get(e.getChannel)

    while (!pendingMessagsList.isEmpty) {
      val event = pendingMessagsList.removeFirst()
      handleMessage(event.getMessage.asInstanceOf[AkkaRemoteProtocol], ctx,event,false)
      if (pendingMessagsList.peekFirst() eq event){
        return
      }
    }
  }


  def handleCnfe(fqn: String, uuid: UUID, uuidProto: UuidProtocol, ctx: ChannelHandlerContext, m: MessageEvent, addLast:Boolean): Unit = {
    pendingRclClass.set(m.getChannel, (fqn, uuid))
       if (addLast)
        pendingMessages.get(m.getChannel).addLast(m)
      else
        pendingMessages.get(m.getChannel).addFirst(m)

    val bcreq = ByteCodeRequestProtocol.newBuilder().setFqn(ByteString.copyFrom(fqn.getBytes)).setRclId(uuidProto).build()
    m.getChannel.write(RemoteEncoder.encode(bcreq)).awaitUninterruptibly()
//    m.getChannel.write(RemoteEncoder.encode(bcreq)).addListener(new ChannelFutureListener {
//      def operationComplete(p1: ChannelFuture) {
//        // todo if this fails 3 more times just forward all original events to super
//        // todo make utility class that can retry
//      }
//    })
  }

  def handleMessage(arp: AkkaRemoteProtocol, ctx: ChannelHandlerContext, e: MessageEvent, addLast:Boolean): Unit = {
    val channel = e.getChannel
    // if we are waiting on bytecode response lets put the pending messages on a queue
    if (pendingRclClass.get(channel) != null) {
      if (addLast)
        pendingMessages.get(channel).addLast(e)
      else
        pendingMessages.get(channel).addFirst(e)
      return
    }

    // if there is no RCL Id attached we can proceeded no problem we assume it is a java. or scala. something stuff
    val message = arp.getMessage.getMessage
    if (!message.hasRclId) {
      super.messageReceived(ctx,e)
      return
    }

    val uuidProto = message.getRclId
    val uuid = RemoteClassLoading.fromUuidProto(uuidProto)
    // todo figure a better way this is actually good way if the next handler would take deserialized message
    try {
      MessageSerializer.deserialize(message, Some(RemoteClassLoading.rclsByUuid.get(uuid)))
      // deserialize was success we can proceed no problem
      super.messageReceived(ctx, e)
    }
    catch {
      case cnfe: ClassNotFoundException => handleCnfe(cnfe.getMessage, uuid, uuidProto, ctx, e,addLast)
      // todo it is possible to get other exceptions aswell
    }
  }

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    e.getMessage match {
      case arp: AkkaRemoteProtocol if arp.hasBcreq => handleBcreq(arp.getBcreq, ctx, e)
      case arp: AkkaRemoteProtocol if arp.hasBcresp => handleBcresp(arp.getBcresp, ctx, e)
      case arp: AkkaRemoteProtocol if arp.hasMessage => handleMessage(arp, ctx, e, true)
      case _ => super.messageReceived(ctx, e)
    }
  }


}


