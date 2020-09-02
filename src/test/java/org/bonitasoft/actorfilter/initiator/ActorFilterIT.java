package org.bonitasoft.actorfilter.initiator;

import com.bonitasoft.deployer.client.model.User;
import com.bonitasoft.deployer.client.model.UserTask;
import lombok.extern.slf4j.Slf4j;
import org.bonitasoft.engine.bpm.bar.BusinessArchive;
import org.bonitasoft.engine.bpm.bar.BusinessArchiveBuilder;
import org.bonitasoft.engine.bpm.bar.actorMapping.Actor;
import org.bonitasoft.engine.bpm.bar.actorMapping.ActorMapping;
import org.bonitasoft.engine.bpm.process.impl.ProcessDefinitionBuilder;
import org.bonitasoft.engine.expression.ExpressionBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@Testcontainers
class ActorFilterIT extends AbstractBonitaIT {

    private long processId;
    private User matti;
    private User aleksi;
    private User juho;
    private User processManager;

    @AfterEach
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @BeforeEach
    public void setUp() throws Exception {

        super.setUp();

        loginAs("install", "install");

        // Declare users
        matti = addUser("matti");
        aleksi = addUser("aleksi");
        juho = addUser("juho");
        processManager = addAdministrator("processManager");

        // Create a process using the filter
        BusinessArchiveBuilder barBuilder = newBarBuilder();

        final String deliveryActor = "Delivery men";

        final ProcessDefinitionBuilder processBuilder = new ProcessDefinitionBuilder().createNewInstance("ProcessWithActorFilter", definitionVersion);
        processBuilder.addActor(deliveryActor).addDescription("Delivery all day and night long").setActorInitiator(deliveryActor);
        processBuilder.addUserTask("step1", deliveryActor)
                .addUserFilter("initiator", implementationId, implementationVersion)
                .addInput("autoAssign", new ExpressionBuilder().createConstantBooleanExpression(true));
        barBuilder.setProcessDefinition(processBuilder.done());

        ActorMapping actorMapping = new ActorMapping();
        Actor actor = new Actor(deliveryActor);
        actor.addUser(matti.getUserName());
        actor.addUser(aleksi.getUserName());
        actor.addUser(juho.getUserName());
        actorMapping.addActor(actor);
        barBuilder.setActorMapping(actorMapping);

        BusinessArchive bar = barBuilder.done();
        processId = deployBar(bar);

        logout();

    }

    @Test
    void testProcessInitiatorUserFilterTest() throws Exception {
        loginAs("matti", "bpm");
        long processInstanceId = bonitaClient.startProcess(processId);
        logout();

        loginAs("install", "install");
        List<UserTask> userTasks = bonitaClient.searchUserTask(processInstanceId);
        userTasks.forEach(System.out::println);
//        waitForUserTask(processInstance, "step1");
//        checkAssignations();
        System.out.println("Pouet");
        logout();
    }

    @Test
    void bonita_should_be_running() {
        assertThat(bonitaContainer.isRunning()).isTrue();
        assertThat(bonitaHost).isNotBlank();
        assertThat(bonitaPort).isNotZero();
    }
}
