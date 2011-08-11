package akka.remote.rcl

import akka.remote.netty.NettyRemoteSupport
import akka.actor.Actor._
import akka.actor.Actor
import org.scalatest.matchers.MustMatchers
import akka.event.EventHandler.DefaultListener
import org.scalatest.{BeforeAndAfterAll, FeatureSpec, GivenWhenThen}
import akka.util.duration._
import org.jboss.shrinkwrap.api.spec.JavaArchive
import java.io.File
import com.google.common.io.Files
import annotation.tailrec
import collection.mutable.ListBuffer
import com.google.common.collect.Lists
import org.jboss.shrinkwrap.api.{ArchivePaths, ShrinkWrap}
import org.jboss.shrinkwrap.api.classloader.ShrinkWrapClassLoader
import org.jboss.shrinkwrap.api.importer.ExplodedImporter
import java.lang.ClassNotFoundException

object RclSpecSettings {
  val host = "localhost"
  val port = 4270
  val timeout = 2.seconds

  val maxNumberToSend = 3
  val runs = 1
}

class RclSpec extends FeatureSpec with GivenWhenThen with BeforeAndAfterAll with MustMatchers {


  // we will operate on Akka loaded in two seperate class loaders
  def createAkkaJar(): JavaArchive = {
    val akkaJar = ShrinkWrap.create(classOf[JavaArchive])
    // I HATE SBT whoever made SBT deserves a good Neal Ford style slap in the face. Look ma, I am going to make a new build tool...SLAPPPP!!!
    // A simple matter of System.getProperty("java.class.path") becomes a nightmare

    // ok lets get the location on disk where this class is loaded
    val url = this.getClass.getClassLoader.getResource(this.getClass.getName.replace('.', '/') + ".class")
    var file = new File(url.getFile)
    // lets find the project root we assume we have root/remote/target/test-classes/my/package/this.class
    val packageDepth = this.getClass.getClass.getName.count(_ == '.') + 4 // to get to the root

    for (i <- 0.to(packageDepth)) {
      file = file.getParentFile
    }

    val classesDirectories = findAllClassesDirectories(file, ListBuffer[File]())

    classesDirectories.foreach((file) => {
      akkaJar.merge(ShrinkWrap.create(classOf[JavaArchive]).as(classOf[ExplodedImporter]).importDirectory(file).as(classOf[JavaArchive]));
    })

    akkaJar
  }

  private def findAllClassesDirectories(root: File, accumulator: ListBuffer[File]): ListBuffer[File] = {
    if (root.isDirectory && !root.getName.startsWith(".")) {
      if (root.getName == "classes" || root.getName == "test-classes") {
        accumulator.append(root)
      } else {
        root.listFiles().foreach(findAllClassesDirectories(_, accumulator))
      }
    }
    accumulator
  }

  class ScalaLibraryOnlyClassLoader extends ClassLoader(this.getClass.getClassLoader) {
    // we are non-delegating class loader except for scala and java
    override def loadClass(fqn: String, resolve: Boolean) = {
      if (!fqn.startsWith("akka.")) super.loadClass(fqn, resolve) else throw new ClassNotFoundException(fqn)
    }
  }

  val nodeA = new ShrinkWrapClassLoader(new ScalaLibraryOnlyClassLoader, createAkkaJar())

  feature("Remote Class Loading") {
    info("If the bytecode required to deserialize the message is not on the classpath")
    info("It will be loaded transparently by akka")


    val actor = remote.actorFor("test", RclSpecSettings.host, RclSpecSettings.port)
    val messageOrderActor = remote.actorFor("message-order-test", RclSpecSettings.host, RclSpecSettings.port)

    scenario("Sending java. and scala. code should work as expected") {
      given("Simple string should return a simple response")
      val reply = (actor.?("PING")(timeout = RclSpecSettings.timeout)).as[String]
      reply.get must equal("PONG")
    }

    scenario("A simple message - Bar - is sent from test to the remote test node") {
      given("a message with unknown class on the remote test node")
      val msg = Messages.newBar()
      when("the message is sent to the server node")

      val reply = (actor.?(msg)(timeout = RclSpecSettings.timeout)).as[AnyRef]

      then("remote node should ask the client node to supply it with the bytecode")
      and("complete then complete the message invocation normally")

      msg.getClass must be eq (reply.get.getClass)
    }

    scenario("Two messages with same class name but different bytecode are sent") {
      given("Two messages with same FQN but different classloader")
      val msg1 = Messages.newFooSimpleInheritenceA
      val msg2 = Messages.newFooSimpleInheritenceB

      when("the two messages are received on the server node")
      val reply1 = (actor.?(msg1)(timeout = RclSpecSettings.timeout)).as[AnyRef]
      val reply2 = (actor.?(msg2)(timeout = RclSpecSettings.timeout)).as[AnyRef]

      then("remote node should resolve the missing bytecode correctly with classloaders in mind")
      and("complete the invocations normally")

      reply1.get.getClass must equal(msg1.getClass)
      reply2.get.getClass must equal(msg2.getClass)
    }

    scenario("A message with cyclic references is sent.") {
      given("A message with cyclic references")
      val msg = Messages.newBazCyclic

      when("the two messages are received on the server node")
      val reply1 = (actor.?(msg)(timeout = RclSpecSettings.timeout)).as[AnyRef]

      then("remote node should resolve the missing bytecode correctly with classloaders in mind")
      and("complete the invocations normally")

      val clazz = reply1.get.getClass
      val clazz1 = msg.getClass
      println(clazz1.getClassLoader)
      println(clazz.getClassLoader)
      clazz must equal(clazz1)
    }

    scenario("A message ordering must be respect at all times specially when RCL request happens") {
      for (i <- 1 to RclSpecSettings.runs) {
        messageOrderActor ! Messages.newDummyAlwaysNewCl
        for (n <- 1 to RclSpecSettings.maxNumberToSend) messageOrderActor ! n
        val outOfOrder = (messageOrderActor.?(MessageOrderTestConstants.isOutOfOrder)(timeout = RclSpecSettings.timeout)).as[Boolean]
        outOfOrder.get must equal(false)
      }
    }

    scenario("If bytecode is not available the message should be forwared to remote server/client") {
      pending
    }
    scenario("If bytecode response is not recevied after some time the code should blacklist the class and forward to remote server/client") {
      pending
    }
    scenario("If more than specified amount of messages are recevied while waiting for BCResponse the overflowing messages should be dropped") {
      pending
    }
    scenario("The user specified class loader must be respected by client and serverm") {
      pending
    }


  }

  override def beforeAll() = {
    nodeA.loadClass(classOf[NodeA_Start].getName).newInstance()
    super.beforeAll()
  }

  override def afterAll() = {
    nodeA.loadClass(classOf[NodeA_Stop].getName).newInstance()
    remote.shutdownClientModule()
    remote.shutdownServerModule()
    super.afterAll()
  }

}

class NodeA_Start {
  println("NodeA start")
  remote.start(RclSpecSettings.host, RclSpecSettings.port)
  remote.register("test", actorOf[EchoActor])
  remote.register("message-order-test", actorOf(new MessageOrderTestActor(RclSpecSettings.maxNumberToSend)))
}

class NodeA_Stop {
  println("NodeA stop")
  remote.shutdown()
  registry.shutdownAll()
  remote.shutdownClientModule()
  remote.shutdownServerModule()
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
class MessageOrderTestActor(val maxNumberToSend: Int) extends Actor {

  assert(maxNumberToSend >= 0, "N must be non-negative")

  @volatile var expecting: Int = MessageOrderTestConstants.X
  @volatile var outOfOrder = false

  def receive = {
    case MessageOrderTestConstants.isOutOfOrder => self.reply(outOfOrder)
    case i: Int if expecting == i => if (expecting == maxNumberToSend) expecting = MessageOrderTestConstants.X else expecting += 1;
    case ref: AnyRef if expecting == MessageOrderTestConstants.X => expecting = 1
    case _ => outOfOrder = true
  }
}

class EchoActor extends Actor {
  def receive = {
    case "PING" => {
      self.reply("PONG")
    }
    case ref: AnyRef => {
      println("Actor received " + ref.getClass.getName)
      self.reply(ref)
    }
    case _ => // discard unknown
  }
}