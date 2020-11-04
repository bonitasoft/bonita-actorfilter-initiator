package org.bonitasoft.actorfilter.initiator;

import com.bonitasoft.deployer.client.BonitaClient;
import com.bonitasoft.deployer.client.BonitaClientBuilder;
import com.bonitasoft.deployer.client.exception.ClientException;
import com.bonitasoft.deployer.client.internal.services.model.CreateUser;
import com.bonitasoft.deployer.client.model.User;
import com.bonitasoft.deployer.client.policies.ProcessImportPolicy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.bonitasoft.engine.bpm.bar.BarResource;
import org.bonitasoft.engine.bpm.bar.BusinessArchive;
import org.bonitasoft.engine.bpm.bar.BusinessArchiveBuilder;
import org.bonitasoft.engine.bpm.bar.BusinessArchiveFactory;
import org.bonitasoft.engine.io.IOUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;

import static com.bonitasoft.deployer.client.model.Profile.Profiles;

@Slf4j
public abstract class AbstractBonitaIT {

    protected String bonitaHost;
    protected Integer bonitaPort;
    protected GenericContainer<?> bonitaContainer;
    protected String bonitaVersion;

    protected String definitionId;
    protected String definitionVersion;
    protected String implementationId;
    protected String implementationVersion;

    protected File workDir;
    protected File zipFile;

    protected BonitaClient bonitaClient;

    @AfterEach
    public void tearDown() throws Exception {
        stopContainer();
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
        if (workDir.exists()) {
            workDir.delete();
        }
        bonitaClient = BonitaClientBuilder.bonitaClient("http://" + bonitaHost + ":" + bonitaPort + "/bonita/")
                .importNotifier(event -> log.debug("Import event: {}", event)).build();

    }

    private void startContainer() {
        bonitaContainer = new GenericContainer<>("bonita:" + bonitaVersion).withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/bonita"));
        bonitaContainer.start();
        bonitaHost = bonitaContainer.getHost();
        bonitaPort = bonitaContainer.getFirstMappedPort();
    }

    private void loadConfiguration() throws IOException {

        Properties config = new Properties();
        String configPath = "bonita.properties";
        try (InputStream configStream = getClass().getClassLoader().getResourceAsStream(configPath)) {
            config.load(configStream);

            String artifactId = config.getProperty("projectArtifactId");
            String artifactVersion = config.getProperty("projectVersion");

            bonitaVersion = System.getProperty("bonitaVersion", config.getProperty("bonitaVersion"));
            definitionId = config.getProperty("definitionId");
            definitionVersion = config.getProperty("definitionVersion");
            implementationId = config.getProperty("implementationId");
            implementationVersion = config.getProperty("implementationVersion");

            workDir = new File("target/bonita-it-tmp");
            zipFile = new File("target/" + artifactId + "-" + artifactVersion + ".zip");
        } catch (IOException e) {
            System.err.println("Unable to load test configuration file : " + configPath);
            throw e;
        }
    }

    protected BusinessArchiveBuilder newBarBuilder() throws Exception {
        final BusinessArchiveBuilder barBuilder = new BusinessArchiveBuilder().createNewBusinessArchive();

        // Unzip
        try (InputStream zipStream = new FileInputStream(zipFile)) {
            IOUtil.unzipToFolder(zipStream, workDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read zip file:" + zipFile.getName(), e);
        }

        // bar builder - impl
        String implementationFileName = definitionId + ".impl";
        File implFile = new File(workDir, implementationFileName);
        try (final InputStream impl = new FileInputStream(implFile)) {
            barBuilder.addUserFilters(new BarResource(implementationFileName, IOUtils.toByteArray(impl)));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read impl file: " + implFile.getName(), e);
        }

        // bar builder - classpath
        File classpath = new File(workDir, "classpath");
        Arrays.stream(classpath.listFiles()).forEach(jar -> {
            try (FileInputStream stream = new FileInputStream(jar)) {
                barBuilder.addClasspathResource(new BarResource(jar.getName(), IOUtils.toByteArray(stream)));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read jar file:" + jar.getName(), e);
            }
        });

        return barBuilder;
    }

    /**
     * Deploy a process (as a bar)
     *
     * @param businessArchive
     * @return the deployed process id
     * @throws Exception
     */
    protected long deployBar(BusinessArchive businessArchive) throws Exception {
        File barFile = new File(workDir, "process.bar");
        if (barFile.exists()) {
            barFile.delete();
        }
        BusinessArchiveFactory.writeBusinessArchiveToFile(businessArchive, barFile);
        return bonitaClient.importProcess(barFile, ProcessImportPolicy.REPLACE_DUPLICATES);
    }

    protected User addUser(String username) throws IOException, ClientException {
        User user = bonitaClient.createUser(new CreateUser().setUserName(username).setFirstName(username).setPassword("bpm"));
        bonitaClient.addUserToProfile(user.getUserName(), Profiles.USER.getValue());
        return user;
    }

    protected User addAdministrator(String username) throws IOException, ClientException {
        User user = bonitaClient.createUser(new CreateUser().setUserName(username).setFirstName(username).setPassword("bpm"));
        bonitaClient.addUserToProfile(user.getUserName(), Profiles.ADMINISTRATOR.getValue());
        return user;
    }

    protected void logout() throws Exception {
        bonitaClient.logout();
    }

    protected void loginAs(String username, String password) throws Exception {
        bonitaClient.login(username, password);
    }


}
