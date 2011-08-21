package akka.arquillian.tests.webapp.dummy;

import akka.actor.Actors;
import akka.arquillian.tests.webapp.dummy.actors.EchoActor;
import akka.arquillian.tests.webapp.dummy.actors.MessageOrderTestActor;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import static akka.actor.Actors.remote;

/**
 * Created by IntelliJ IDEA.
 * User: avrecko
 * Date: 8/15/11
 * Time: 5:10 PM
 * To change this template use File | Settings | File Templates.
 */
public class Bootstrap implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent) {
        System.out.println("Started Server Bootstrap");
        remote().start("localhost", 4273);

        remote().register("echo-actor", Actors.actorOf(EchoActor.class));
        remote().register("message-order-actor", Actors.actorOf(MessageOrderTestActor.class));
    }

    @Override
    public void contextDestroyed(ServletContextEvent servletContextEvent) {
        System.out.println("Server shutdown");
        remote().shutdown();
    }
}
