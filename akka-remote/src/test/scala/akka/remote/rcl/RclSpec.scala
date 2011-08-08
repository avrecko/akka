package akka.remote.rcl

import akka.remote.netty.NettyRemoteSupport
import akka.actor.Actor._
import akka.actor.Actor
import org.scalatest.matchers.MustMatchers
import akka.event.EventHandler.DefaultListener
import org.scalatest.{BeforeAndAfterAll, FeatureSpec, GivenWhenThen}
import akka.util.duration._

class RclSpec extends FeatureSpec with GivenWhenThen with BeforeAndAfterAll with MustMatchers {

  val host = "localhost"
  val port = 4270
  val timeout = 2.seconds

  val maxNumberToSend = 10
  val runs = 10


  feature("Remote Class Loading") {
    info("If the bytecode required to deserialize the message is not on the classpath")
    info("It will be loaded transparently by akka")


    val actor = remote.actorFor("test", host, port)
    val messageOrderActor = remote.actorFor("message-order-test", host, port)

    scenario("Sending java. and scala. code should work as expected") {
      given("Simple string should return a simple response")
      val reply = (actor.?("PING")(timeout=timeout)).as[String]
      reply.get must equal("PONG")
    }

    scenario("A simple message - Bar - is sent from test to the remote test node") {
      given("a message with unknown class on the remote test node")
      val msg = Messages.newBar()
      when("the message is sent to the server node")

      val reply = (actor.?(msg)(timeout=timeout)).as[AnyRef]

      then("remote node should ask the client node to supply it with the bytecode")
      and("complete then complete the message invocation normally")

      msg.getClass must be eq(reply.get.getClass)
    }

    scenario("Two messages with same class name but different bytecode are sent") {
      given("Two messages with same FQN but different classloader")
      val msg1 = Messages.newFooSimpleInheritenceA
      val msg2 = Messages.newFooSimpleInheritenceB

      when("the two messages are received on the server node")
      val reply1 = (actor.?(msg1)(timeout= timeout)).as[AnyRef]
      val reply2 = (actor.?(msg2)(timeout= timeout)).as[AnyRef]

      then("remote node should resolve the missing bytecode correctly with classloaders in mind")
      and("complete the invocations normally")

      reply1.get.getClass must equal(msg1.getClass)
      reply2.get.getClass must equal(msg2.getClass)
    }

    scenario("A message with cyclic references is sent.") {
      given("A message with cyclic references")
      val msg = Messages.newBazCyclic

      when("the two messages are received on the server node")
      val reply1 = (actor.?(msg)(timeout=timeout)).as[AnyRef]

      then("remote node should resolve the missing bytecode correctly with classloaders in mind")
      and("complete the invocations normally")

      val clazz = reply1.get.getClass
      val clazz1 = msg.getClass
      println(clazz1.getClassLoader)
      println(clazz.getClassLoader)
      clazz must equal(clazz1)
    }

    scenario("A message ordering must be respect at all times specially when RCL request happens"){
      for (i <- 1 to runs){
        messageOrderActor ! Messages.newDummyAlwaysNewCl
        for (n <- 1 to maxNumberToSend) messageOrderActor ! n
        val outOfOrder = (messageOrderActor.?( MessageOrderTestConstants.isOutOfOrder)(timeout=timeout)).as[Boolean]
        outOfOrder.get must equal(false)
      }
    }

    scenario("If bytecode is not available the message should be forwared to remote server/client") {  pending }
    scenario("If bytecode response is not recevied the code should retry sending the BCRequest a few more times") {  pending }
    scenario("If more than specified amount of messages are recevied while waiting for BCResponse the overflowing messages should be dropped") { pending }
    scenario("The user specified class loader must be respected by client and serverm") { pending }


  }

  val optimizedLocals = remote.asInstanceOf[NettyRemoteSupport].optimizeLocal.get()

  override def beforeAll() = {
    remote.addListener(actorOf[DefaultListener].start)
    // make sure all calls are made via remoting (with serialization)
    remote.asInstanceOf[NettyRemoteSupport].optimizeLocal.set(false)

    remote.start(host, port)
    remote.register("test", actorOf[EchoActor])
    remote.register("message-order-test", actorOf(new MessageOrderTestActor(maxNumberToSend)))

    super.beforeAll()
  }

  override def afterAll() = {
    //Reset optimizelocal after all tests
    remote.asInstanceOf[NettyRemoteSupport].optimizeLocal.set(optimizedLocals)
    remote.shutdown()
    // todo is it really necessary to shutdown registy to?
    registry.shutdownAll()
    super.afterAll()
  }

}

object MessageOrderTestConstants {
    val X = -1
    val isOutOfOrder = 0
}

/**
 * This actors expect to receive the messages in this order X,1,2,...maxNumberToSend repeated loopRepeats times.
 *
 * The X represents a message who will fail with Class not found exception and the whole RCL thing will happen.
 */
class MessageOrderTestActor(val maxNumberToSend:Int) extends Actor {

  assert(maxNumberToSend >= 0, "N must be non-negative")

  @volatile var expecting:Int = MessageOrderTestConstants.X
  @volatile var outOfOrder = false

  def receive = {
    case MessageOrderTestConstants.isOutOfOrder => self.reply(outOfOrder)
    case i:Int if expecting == i => if (expecting == maxNumberToSend) expecting = MessageOrderTestConstants.X else expecting += 1;
    case ref:AnyRef if expecting == MessageOrderTestConstants.X => expecting = 1
    case _ => outOfOrder = true
  }
}

class EchoActor extends Actor {
  def receive = {
    case "PING" => {
      self.reply("PONG")
    }
    case ref:AnyRef => {
      self.reply(ref)
    }
    case _ => // discard unknown
  }
}