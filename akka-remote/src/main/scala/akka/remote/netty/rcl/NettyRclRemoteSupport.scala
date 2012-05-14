package akka.remote.netty.rcl

import akka.remote.netty.NettyRemoteTransport
import akka.actor.ExtendedActorSystem
import akka.remote.RemoteActorRefProvider

class NettyRclRemoteTransport(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider) extends NettyRemoteTransport(_system, _provider) {

}
