package akka.remote.rcl

import org.objectweb.asm.{ClassReader, ClassWriter}
import org.objectweb.asm.commons.{SimpleRemapper, RemappingClassAdapter}
import com.google.common.io.Resources
import scala.collection.JavaConversions._
import java.io.{ByteArrayInputStream, Serializable}
import java.util.HashMap
import com.google.common.collect.{ImmutableBiMap, ImmutableMap}
import java.util.Map.Entry
/**
 * Provides low-level plumbing needed by RclSpec.
 */
object Messages {

  def newBar(): AnyRef = barClassLoader.loadClass(BAR_FQN).newInstance.asInstanceOf[AnyRef]

  def newFooSimpleInheritenceA: AnyRef = fooVariation1ClassLoader.loadClass(FOO_FQN).newInstance().asInstanceOf[AnyRef]

  def newFooSimpleInheritenceB: AnyRef = fooVariation2ClassLoader.loadClass(FOO_FQN).newInstance().asInstanceOf[AnyRef]

  def newBazCyclic: AnyRef = bazClassLoader.loadClass(BAZ_FQN).newInstance().asInstanceOf[AnyRef]

  def newDummyAlwaysNewCl = new RemappingClassLoader(this.getClass.getClassLoader, ImmutableBiMap.of(
    classOf[DummyPaddedName], DUMMY_FQN)
  ).loadClass(DUMMY_FQN).newInstance().asInstanceOf[AnyRef]

  val FOO_FQN = "akka.remote.rcl.Foo"
  val BAR_FQN = "akka.remote.rcl.Bar"
  val BAZ_FQN = "akka.remote.rcl.Baz"
  val QUX_FQN = "akka.remote.rcl.Qux"
  val DUMMY_FQN = "akka.remote.rcl.Dummy"

  val FOO_RESOURCE = Messages.FOO_FQN.replace('.', '/') + ".class"
  val BAR_RESOURCE = Messages.BAR_FQN.replace('.', '/') + ".class"

  lazy val fooVariation1ClassLoader = new RemappingClassLoader(barClassLoader, ImmutableBiMap.of(
    classOf[FooSimpleInheritenceA], FOO_FQN
  ))

  lazy val fooVariation2ClassLoader = new RemappingClassLoader(barClassLoader, ImmutableBiMap.of(
    classOf[FooSimpleInheritenceB], FOO_FQN
  ))

  lazy val barClassLoader = new RemappingClassLoader(classOf[RemappingClassLoader].getClassLoader, ImmutableBiMap.of(
    classOf[BarPaddedName], BAR_FQN))

  lazy val bazClassLoader = new RemappingClassLoader(classOf[RemappingClassLoader].getClassLoader, ImmutableBiMap.of(
    classOf[BazWithCyclic], BAZ_FQN,
    classOf[QuxPaddedName], QUX_FQN
  ))


}


class BarPaddedName extends Serializable

class FooSimpleInheritenceA extends BarPaddedName

class FooSimpleInheritenceB extends BarPaddedName


class BazWithCyclic extends Serializable {
  @volatile var qux: QuxPaddedName = null;
}

class QuxPaddedName extends Serializable {
  @volatile var baz: BazWithCyclic = null
}

class DummyPaddedName extends Serializable

object ByteCodeRenamer {
  def rename(bytecode: Array[Byte], remappings: ImmutableBiMap[Class[_], String]) = {
    val classReader = new ClassReader(bytecode)

    // rename the class in the bytecode to targeted name
    val classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS)
    val classVisitor = new RemappingClassAdapter(classWriter, new SimpleRemapper(remapToAsm(remappings)))
    classReader.accept(classVisitor, ClassReader.SKIP_DEBUG)
    classWriter.toByteArray
  }

  def remapToAsm(biMap: ImmutableBiMap[Class[_], String]): ImmutableMap[String, String] = {
    val builder = ImmutableMap.builder[String, String]
    for ((a: Class[_], b: String) <- biMap) {
      builder.put(a.getName.replace('.', '/'), b.replace('.', '/'))
    }
    return builder.build()
  }
}

/**When asked to supply a class it will actuall use different class (as defined in remappings) and supply it as if it were named that way */
class RemappingClassLoader(val parent: ClassLoader, val remappings: ImmutableBiMap[Class[_], String], val delegateToParent: Boolean = true)
  extends ClassLoader(parent) {

  val bytecodeCache = new HashMap[String, Array[Byte]]()
  val parentCombinedRemappings: ImmutableBiMap[Class[_], String] = parent match {
    case rcl: RemappingClassLoader => {
      val builder = ImmutableBiMap.builder[Class[_], String]
      builder.putAll(remappings)
      rcl.parentCombinedRemappings.entrySet().foreach((entry: Entry[Class[_], String]) => if (!remappings.containsKey(entry.getKey)) builder.put(entry.getKey, entry.getValue))
      builder.build()
    }

    case _ => remappings
  }

  // we are a delegation model CL so we will override findClass
  override def findClass(requestedClass: String): Class[_] = {
    // we can assume that loadClass failed to find the class
    // caching will be done by loadClass method as well as loadClass is synchronized
    // this methods should not be invoked directly
    // has the request class got a template to use in it's place ?
    val templateClass = remappings.inverse().get(requestedClass)
    if (templateClass != null) {
      // first lets get the bytecode of the existing class
      val bytecode = Resources.toByteArray(templateClass.getResource("/" + templateClass.getName.replace('.', '/') + ".class"))
      val renamedBytecode = ByteCodeRenamer.rename(bytecode, parentCombinedRemappings)

      bytecodeCache.put(requestedClass.replace('.', '/') + ".class", renamedBytecode)
      // lets define the class
      return defineClass(requestedClass, renamedBytecode, 0, renamedBytecode.length)
    }

    throw new ClassNotFoundException(requestedClass)
  }

  def defineClass(fqn: String, bytecode: Array[Byte]): Class[_] = {
    val clazz = defineClass(fqn, bytecode, 0, bytecode.length)
    (resolveClass(_))
    return clazz
  }


  override def loadClass(p1: String) = loadClass(p1, false)

  override def loadClass(p1: String, p2: Boolean): Class[_] = return delegateToParent match {
    case true => super.loadClass(p1, p2)
    case false => if (parentCombinedRemappings.inverse().containsKey(p1)) findClass(p1) else super.loadClass(p1, p2)
  }


  override def getResourceAsStream(resource: String) = {
    val array: Array[Byte] = bytecodeCache.get(resource)
    if (array != null) {
      new ByteArrayInputStream(array)
    } else {
      parent.getResourceAsStream(resource)
    }
  }

  override def getResource(p1: String) = {
    throw new RuntimeException("Not implemented use getResourceAsStream instead.")
  }
}




