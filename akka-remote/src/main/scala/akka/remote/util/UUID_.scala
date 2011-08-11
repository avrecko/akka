package akka.remote.util

import akka.remote.protocol.RemoteProtocol.UuidProtocol
import com.eaio.uuid.UUID

/**
 * Created by IntelliJ IDEA.
 * User: avrecko
 * Date: 8/8/11
 * Time: 10:05 AM
 * To change this template use File | Settings | File Templates.
 */
object UUID_ {

  def toUuid(proto: UuidProtocol) = new UUID(proto.getHigh, proto.getLow)

  def toProto(uuid: UUID) = UuidProtocol.newBuilder().setHigh(uuid.getTime).setLow(uuid.getClockSeqAndNode).build()

}