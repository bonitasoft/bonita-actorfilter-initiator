package org.bonitasoft.actorfilter.initiator;

import com.bonitasoft.deployer.client.BonitaClient;
import com.bonitasoft.deployer.client.BonitaClientBuilder;
import com.bonitasoft.deployer.client.internal.api.CreateUser;
import com.bonitasoft.deployer.client.model.User;
import com.bonitasoft.deployer.client.policies.ProcessImportPolicy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.bonitasoft.engine.bpm.bar.BarResource;
import org.bonitasoft.engine.bpm.bar.BusinessArchive;
import org.bonitasoft.engine.bpm.bar.BusinessArchiveBuilder;
import org.bonitasoft.engine.bpm.bar.BusinessArchiveFactory;
import org.bonitasoft.engine.bpm.bar.actorMapping.Actor;
import org.bonitasoft.engine.bpm.bar.actorMapping.ActorMapping;
import org.bonitasoft.engine.bpm.process.impl.ProcessDefinitionBuilder;
import org.bonitasoft.engine.expression.ExpressionBuilder;
import org.bonitasoft.engine.io.IOUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@Testcontainers
class ActorFilterIT {


    private GenericContainer<?> bonitaContainer;

    private String bonitaHost;
    private Integer bonitaPort;
    private String bonitaVersion;
    private String definitionVersion;
    private String artifactVersion;
    private String artifactName;
    private File workDir;
    private String implementationId;
    private String implementationVersion;
    private File zipFile;

    @AfterEach
    public void tearDown() throws Exception {
        stopContainer();
        File tmp = new File("./target/" + artifactName + "-tmp");
        if (tmp.exists()) {
            tmp.delete();
        }
    }

    private void stopContainer() {
        if (bonitaContainer.isRunning()) {
            bonitaContainer.stop();
        }
    }

    @BeforeEach
    public void setUp() throws Exception {

        loadConfiguration();
        startContainer();

        BonitaClient bonitaClient = BonitaClientBuilder.bonitaClient("http://" + bonitaHost + ":" + bonitaPort + "/bonita/")
                .importNotifier(event -> log.debug("Import event: {}", event)).build();

        bonitaClient.login("install", "install");

        User matti = bonitaClient.createUser(new CreateUser().setUserName("matti").setPassword("bpm"));
        User aleksi = bonitaClient.createUser(new CreateUser().setUserName("aleksi").setPassword("bpm"));
        User juho = bonitaClient.createUser(new CreateUser().setUserName("juho").setPassword("bpm"));
        User processManager = bonitaClient.createUser(new CreateUser().setUserName("processManager").setPassword("bpm"));


        final ExpressionBuilder expressionBuilder = new ExpressionBuilder();
        final String deliveryActor = "Delivery men";

        final BusinessArchiveBuilder businessArchiveBuilder = new BusinessArchiveBuilder().createNewBusinessArchive();


        final ProcessDefinitionBuilder designProcessDefinition = new ProcessDefinitionBuilder()
                .createNewInstance("ProcessWithActorFilter", definitionVersion);
        designProcessDefinition.addActor(deliveryActor).addDescription("Delivery all day and night long");
        designProcessDefinition.addUserTask("step1", deliveryActor)
                .addUserFilter("initiator", "bonita-actorfilter-initiator-impl", artifactVersion)
                .addInput("autoAssign", expressionBuilder.createConstantBooleanExpression(true));
        businessArchiveBuilder.setProcessDefinition(designProcessDefinition.done());

        // Unzip
        try (InputStream zipStream = new FileInputStream(zipFile)) {
            IOUtil.unzipToFolder(zipStream, workDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read jar file:" + zipFile.getName(), e);
        }

        // bar builder - impl
        File implFile = new File(workDir, artifactName + ".impl");
        try (final InputStream impl = new FileInputStream(implFile)) {
            businessArchiveBuilder.addUserFilters(new BarResource(implementationId + "-" + implementationVersion + ".impl", IOUtils.toByteArray(impl)));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read impl file: " + implFile.getName(), e);
        }

        // bar builder - classpath
        File classpath = new File(workDir, "classpath");
        Arrays.stream(classpath.listFiles()).forEach(jar -> {
            try (FileInputStream stream = new FileInputStream(jar)) {
                businessArchiveBuilder.addClasspathResource(new BarResource(jar.getName(), IOUtils.toByteArray(stream)));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read jar file:" + jar.getName(), e);
            }
        });

        ActorMapping actorMapping = new ActorMapping();
        Actor actor = new Actor(deliveryActor);
        actor.addUser(matti.getUserName());
        actor.addUser(aleksi.getUserName());
        actor.addUser(juho.getUserName());
        actorMapping.addActor(actor);
        businessArchiveBuilder.setActorMapping(actorMapping);

        BusinessArchive businessArchive = businessArchiveBuilder.done();
        File barFile = new File(workDir, "process.bar");
        BusinessArchiveFactory.writeBusinessArchiveToFile(businessArchive, barFile);

        bonitaClient.importProcess(barFile, ProcessImportPolicy.REPLACE_DUPLICATES);

        bonitaClient.logout();

    }

    private void startContainer() {
        bonitaContainer = new GenericContainer<>("bonita:" + bonitaVersion).withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/bonita"));
        bonitaContainer.start();
        bonitaHost = bonitaContainer.getHost();
        bonitaPort = bonitaContainer.getFirstMappedPort();
    }

    private void loadConfiguration() {

        Properties config = new Properties();
        String configPath = "test.properties";
        try (InputStream configStream = getClass().getClassLoader().getResourceAsStream(configPath)) {
            config.load(configStream);
        } catch (IOException e) {
            System.err.println("Unable to load test configuration file : " + configPath);
        }
        artifactName = config.getProperty("projectArtifactId");
        artifactVersion = config.getProperty("projectVersion");
        bonitaVersion = config.getProperty("bonitaVersion");
        definitionVersion = config.getProperty("definitionVersion");
        implementationVersion = config.getProperty("implVersion");
        implementationId = config.getProperty("implId");

        workDir = new File("target/" + artifactName + "-tmp");
        zipFile = new File("target/" + artifactName + "-" + artifactVersion + ".zip");
    }

//    @Test
//    void testProcessInitiatorUserFilterTest() throws Exception {
//        loginOnDefaultTenantWith("matti", "bpm");
//        final ProcessInstance processInstance = getProcessAPI().startProcess(definition.getId());
//
//        waitForUserTask(processInstance, "step1");
//        checkAssignations();
//    }

    @Test
    void bonita_should_be_running() {
        assertThat(bonitaContainer.isRunning()).isTrue();
        assertThat(bonitaHost).isNotBlank();
        assertThat(bonitaPort).isNotZero();

        System.out.println("Pouet");
    }
}
