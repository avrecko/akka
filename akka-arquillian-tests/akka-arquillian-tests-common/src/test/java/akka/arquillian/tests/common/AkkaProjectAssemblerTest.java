package akka.arquillian.tests.common;

import junit.framework.TestCase;

import java.io.File;
import java.util.List;

import static akka.arquillian.tests.common.AkkaProjectAssembler.AkkaProject.*;

/**
 * Created by IntelliJ IDEA.
 * User: avrecko
 * Date: 8/21/11
 * Time: 4:18 PM
 * To change this template use File | Settings | File Templates.
 */
public class AkkaProjectAssemblerTest extends TestCase {
    public void testAssemble() throws Exception {
        AkkaProjectAssembler assembler = new AkkaProjectAssembler(this.getClass());
        List<File> assemble = assembler.assemble(AKKA_ACTOR, AKKA_TESTKIT, AKKA_STM, AKKA_ACTOR_TESTS, AKKA_TYPED_ACTOR, AKKA_REMOTE);
        // if no exception is thrown that is half the battle
        assertEquals(6, assemble.size());
        File firstProjectClasses = assemble.get(0);
        assertTrue(firstProjectClasses.isDirectory());
        assertTrue(firstProjectClasses.getAbsolutePath().contains(AKKA_ACTOR.toProjectDir()));
        assertTrue(firstProjectClasses.getAbsolutePath().endsWith("classes"));
    }
}
