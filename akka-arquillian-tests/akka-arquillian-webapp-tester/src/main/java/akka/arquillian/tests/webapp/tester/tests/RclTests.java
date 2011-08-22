package akka.arquillian.tests.webapp.tester.tests;

import akka.actor.ActorRef;
import akka.actor.Actors;
import akka.arquillian.tests.common.Settings;
import akka.arquillian.tests.common.classloader.RedefiningClassLoader;
import akka.arquillian.tests.common.remoteTests.RemoteTestCase;
import akka.arquillian.tests.webapp.tester.classes.Bar;
import akka.arquillian.tests.webapp.tester.classes.BazCyclic;
import akka.arquillian.tests.webapp.tester.classes.Foo;
import akka.arquillian.tests.webapp.tester.classes.TesterException;
import com.google.common.base.Preconditions;
import com.google.inject.Singleton;
import scala.Option;

/**
 * Created by IntelliJ IDEA.
 * User: avrecko
 * Date: 8/18/11
 * Time: 6:48 PM
 * To change this template use File | Settings | File Templates.
 */
@Singleton
public class RclTests extends RemoteTestCase {

    private final ActorRef echoActor = Actors.remote().actorFor("echo-actor", Settings.HOST, Settings.PORT);
    private final ActorRef msgOrderActor = Actors.remote().actorFor("message-order-actor", Settings.HOST, Settings.PORT);
    private final ActorRef exceptionActor = Actors.remote().actorFor("exception-actor", Settings.HOST, Settings.PORT);


//    /**
//     * Java lang stuff should not interfere with the remote class loading
//     */
//    public void testSendingOfJavaLangStuff() {
//        String pong = (String) echoActor.ask("PING").get();
//        Preconditions.checkArgument("PONG".equals(pong));
//    }
//
//
//    /**
//     * Sending simple class with inheritence should work without any problems
//     */
//    public void testSimpleClassWithInheritence() {
//        // if cast is successful this is it
//        Foo pong1 = (Foo) echoActor.ask(new Foo()).get();
//        Foo pong2 = (Foo) echoActor.ask(new Foo()).get();
//    }

//    /**
//     * Sending FooPing and expecting FooPong in response. We don't have FooPong on the "Class Path".
//     */
//    public void testNonExistingClassInResponse() {
//        // if cast will be successful if this test is
//        Object fooPong = echoActor.ask(new FooPing()).get();
//        // should be fooPong
//        String name = fooPong.getClass().getName();
//        Preconditions.checkArgument("akka.arquillian.tests.webapp.dummy.classes.FooPong".equals(name));
//        // should be loaded in RemoteClassLoader
//        Class<? extends ClassLoader> remoteClassLoader = fooPong.getClass().getClassLoader().getClass();
//        String name1 = remoteClassLoader.getName();
//        Preconditions.checkArgument(name1.endsWith("RemoteClassLoader"));
//
//        Object fooPong2 = echoActor.ask(new FooPing()).get();
//        Preconditions.checkArgument(fooPong.getClass() == fooPong2.getClass());
//    }

//    public void testCyclicReferences() {
//        // if cast is successful we can be sure cylic references work
//        BazCyclic reply = (BazCyclic) echoActor.ask(new BazCyclic()).get();
//    }

//    public void testMessageOrder() throws Throwable {
//        for (int i = 0; i <= Settings.MSG_ORDER_TEST_REPEAT_LOOP; i++) {
//            msgOrderActor.tell(new RedefiningClassLoader(Bar.class, Foo.class).loadClass(Foo.class.getName()).newInstance()); // we just send something that is not on the classpath
//            for (int n = 1; n <= Settings.MSG_ORDER_TEST_MAX_NUMBER_SENT; n++) {
//                msgOrderActor.tell(Integer.valueOf(n));
//            }
//            Boolean correctOrder = (Boolean) msgOrderActor.ask(Settings.MSG_ORDER_TEST_CORRECT_ORDER_QUESTION_TOKEN).get();
//            Preconditions.checkArgument(correctOrder);
//        }
//    }


}
