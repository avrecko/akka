Quick notes on Arquillian test by Alen Vrečko on 2011-08-22

To run integration tests:

You need ant (optional), maven and sbt.

From the integration tests root directory - akka-arquillian-tests run ant

akka-arquillian-tests bash$ ant

1. This will publish required akka.jars into your local maven repository via sbt.
2. The same akka.jars from the repo will be used in 2 war files that get created:
* The webapp-dummy (has remote actors that will be contacted by rcl tests)
* The webapp-tester (has the RclTest that get invoked via calling the /tests servlet)
via mvn install
3. mvn test is run that will invoke Arquillian framework tests that will deploy the 2 mentioned wars into the supported containers and run the tests via invoking the tests servlet.

Arquillian is pretty much a work in progress. We are using arqullian CR1 it has only 2 fully bug free containers.
* Jetty7
* GlassFish 3.1

The rest of the containers are problematic and we will expand to support 
Jetty6.1, Tomcat6-7, JBoss6-7, WebLogic, WebSphere7-8 when the Arqullian project matures after some months from writing this.

To manually tests the Rcl Tests in any Servlet2.5 compatible server follow this steps:
1. run sbt 'project akka-remote' publish from akka root. This will instal the akka jars into your maven repository
2. run mvn install -Dmaven.test.skip=true from akka-arquillian-tests this will create 2 war files

akka-arquillian-webapp-dummy/target/akka-arquillian-webapp-dummy-1.0-SNAPSHOT.war
akka-arquillian-webapp-tester/target/akka-arquillian-webapp-tester-1.0-SNAPSHOT.war

3. Deploy both wars to a WebServer of your choice. Note: We don't use any servlet filters so it is safe to deploy to WebSphere as war file. Note: In WebSphere servlet filters get ignored unless deployed as ear.
4. After deploying the wars go to http://localhost:9090//akka-arquillian-webapp-tester-1.0-SNAPSHOT/tests

The path can be different to reflect your choice of name for the war files etc. What is important is that you invoke the Tests servlet via going to /tests.
You will get a JSON response that contains an array of test method and a flag successful and also the stack trace and message if applicable.

 


