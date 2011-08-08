package akka.remote.rcl

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

/**
 * Test for Messages
 */
class MessagesSpec extends WordSpec with MustMatchers {

  info("For testing the remote class loading within the same sbt project we are redefining some classed on the fly.")
  info("Since we redefine the class they are not seen by the class loader that loaded the client-sever set")

  "Messages" should {

    "Provide independant Bar class that has no dependencies on other classes." in {
      val bar = Messages.newBar()
      bar.getClass.getName must equal (Messages.BAR_FQN)
    }

    "Provide Foo (from FooV1) and Foo (from FooV2) existing in different CLs extening Bar defined in shared CL" in {
      val fooA = Messages.newFooSimpleInheritenceA
      val fooB = Messages.newFooSimpleInheritenceB
      val clazzA = fooA.getClass
      val clazzB = fooB.getClass

      clazzA.getClassLoader must not eq (clazzB.getClassLoader)
      clazzA.getName must equal (clazzB.getName)
      clazzA.getName must equal (Messages.FOO_FQN)

      val clazzASuper = clazzA.getSuperclass
      val clazzBSuper = clazzB.getSuperclass

      clazzASuper.getClassLoader must not eq (clazzA.getClassLoader)
      clazzASuper.getClassLoader must be eq (clazzBSuper.getClassLoader)

      clazzASuper.getName must equal (Messages.BAR_FQN)
    }

    "Provide Baz that has Qux in a cyclic dependency defined in same CL" in {
      val baz = Messages.newBazCyclic
      val clazz = baz.getClass
      clazz.getName must equal (Messages.BAZ_FQN)

      val superclass = clazz.getSuperclass
      superclass.getName must equal  (Messages.QUX_FQN)

      clazz.getClassLoader must equal  (superclass.getClassLoader)
    }

    "Provide Dummy that is always created in a different Class Loader" in {
      val dummy1 = Messages.newDummyAlwaysNewCl
      val dummy2 = Messages.newDummyAlwaysNewCl

      dummy1.getClass.getName must equal (Messages.DUMMY_FQN)
      dummy1.getClass.getClassLoader must not eq (dummy2.getClass.getClassLoader)
    }
  }
}