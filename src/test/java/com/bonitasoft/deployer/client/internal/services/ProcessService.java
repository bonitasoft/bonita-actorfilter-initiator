/*
 * Copyright (C) 2018 Bonitasoft S.A.
 * Bonitasoft is a trademark of Bonitasoft SA.
 * This software file is BONITASOFT CONFIDENTIAL. Not For Distribution.
 * For commercial licensing information, contact:
 * Bonitasoft, 32 rue Gustave Eiffel - 38000 Grenoble
 * or Bonitasoft US, 51 Federal Street, Suite 305, San Francisco, CA 94107
 */
package com.bonitasoft.deployer.client.internal.services;

import static java.lang.String.format;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.bonitasoft.deployer.client.event.ImportNotifier;
import com.bonitasoft.deployer.client.event.ImportWarningEvent;
import com.bonitasoft.deployer.client.exception.ClientException;
import com.bonitasoft.deployer.client.exception.UnauthorizedException;
import com.bonitasoft.deployer.client.internal.BonitaCookieInterceptor;
import com.bonitasoft.deployer.client.internal.api.ProcessAPI;
import com.bonitasoft.deployer.client.internal.converters.RestApiConverter;
import com.bonitasoft.deployer.client.internal.services.model.BusinessArchive;
import com.bonitasoft.deployer.client.model.Process;
import com.bonitasoft.deployer.client.model.ProcessParameter;
import com.bonitasoft.deployer.client.model.ProcessResolutionProblem;
import com.bonitasoft.deployer.client.policies.ProcessImportPolicy;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import retrofit2.Response;

public class ProcessService extends ClientService {

    private final static Logger LOGGER = LoggerFactory.getLogger(ProcessService.class.getName());

    private final RestApiConverter restApiConverter;
    private final BonitaCookieInterceptor bonitaCookieInterceptor;
    private final ProcessAPI processAPI;
    private final ImportNotifier importNotifier;

    public ProcessService(RestApiConverter restApiConverter, BonitaCookieInterceptor bonitaCookieInterceptor,
            ProcessAPI processAPI, ImportNotifier importNotifier) {
        this.restApiConverter = restApiConverter;
        this.bonitaCookieInterceptor = bonitaCookieInterceptor;
        this.processAPI = processAPI;
        this.importNotifier = importNotifier;
    }

    public void importProcess(File content, ProcessImportPolicy policy)
            throws IOException, ClientException {
        LOGGER.info("Deploying process '{}' using policy {} ...", content.getName(), policy.name());
        bonitaCookieInterceptor.checkLogged();
        BusinessArchive businessArchive = BusinessArchive.read(content);
        String processName = businessArchive.getName();
        String processVersion = businessArchive.getVersion();
        Optional<Process> process = getProcess(processName, processVersion);
        if (process.isPresent()) {
            LOGGER.debug("Process '{}' in version '{}' already exists.", processName, processVersion);
            if (policy.equals(ProcessImportPolicy.REPLACE_DUPLICATES)) {
                //simulate a REPLACE_DUPLICATES policy here because it is not implemented in engine side
                LOGGER.debug(
                        "Process '{}' in version '{}' already exists. Policy REPLACE_DUPLICATES: deleting existing process...",
                        processName, processVersion);
                deleteExistingProcess(process.get());
            } else if (policy.equals(ProcessImportPolicy.IGNORE_DUPLICATES)) {
                String message = String.format("Process '%s' in version '%s' already exists. " +
                        "Policy IGNORE_DUPLICATES: skip deployment of existing process.", processName,
                        processVersion);
                importNotifier.post(new ImportWarningEvent(message));
                return;
            } else {
                throw new ClientException(format(
                        "Process '%s' in version '%s' already exists. Policy FAIL_ON_DUPLICATES: deployment aborted.",
                        processName, processVersion));
            }
        }

        RequestBody requestFile = RequestBody.create(MultipartBody.FORM, content);
        MultipartBody.Part body = MultipartBody.Part.createFormData("process.bar", "process.bar",
                requestFile);

        LOGGER.debug("Uploading process file...");
        Response<String> responseUpload = processAPI.uploadContent(body).execute();
        checkResponse(responseUpload);
        LOGGER.debug("Process file uploaded successfully.");

        String uploadedPageZipName = restApiConverter.buildSimpleJson("fileupload", responseUpload.body());
        Response<Process> responseImport = processAPI.add(uploadedPageZipName).execute();
        checkResponse(responseImport);
        LOGGER.info("Process deployed successfully.");

        final Process processDeployed = responseImport.body();
        if (processDeployed.getConfigurationState() == Process.ConfigurationState.RESOLVED) {
            LOGGER.info("Process '{}' ({}) is resolved. Activating process...", processName, processVersion);
            long processId = processDeployed.getId();
            try {
                String payload = restApiConverter.buildSimpleJson("activationState", "ENABLED");
                Response<ResponseBody> responseProcessActivation = processAPI.update(processId, payload).execute();
                checkResponse(responseProcessActivation);
                LOGGER.info("Process activated successfully.");
            } catch (IOException e) {
                LOGGER.debug(e.getMessage());
                String message = format(
                        "Failed to activate the process %s. This is certainly due to configuration issues, see details below.",
                        content.getName());
                importNotifier.post(new ImportWarningEvent(message));

                LOGGER.debug("Retrieving process activation problems...");
                Response<List<ProcessResolutionProblem>> problems = processAPI
                        .processResolutionProblem(0, 20, "process_id=" + processId).execute(); // 20 looks enough for a single shot.
                checkResponse(problems);
                LOGGER.debug("Process activation problems retrieved successfully.");
                problems.body().stream()
                        .map(ProcessResolutionProblem::toString)
                        .forEach(LOGGER::warn);
            }
        } else {
            LOGGER.info("Process '{}' ({}) is unresolved. It cannot be activated for now.", processName,
                    processVersion);
        }
    }

    private void deleteExistingProcess(Process process) throws IOException, UnauthorizedException {
        if (Objects.equals(process.getActivationState(), Process.ActivationState.ENABLED)) {
            LOGGER.info("Deactivating existing process...");
            String payload = restApiConverter.buildSimpleJson("activationState", "DISABLED");
            Response<ResponseBody> responseProcessActivation = processAPI.update(process.getId(), payload)
                    .execute();
            checkResponse(responseProcessActivation);
            LOGGER.info("Existing process deactivated.");
        }
        String payload = restApiConverter.buildSimpleJson(Arrays.asList(String.valueOf(process.getId())));
        Response<ResponseBody> response = processAPI.delete(payload).execute();
        checkResponse(response);
        LOGGER.debug("Existing process deleted.");
    }

    public Optional<Process> getProcess(String name, String version) throws IOException {
        LOGGER.debug("Retrieving process '{}' with version '{}'", name, version);
        List<Process> body = processAPI.search(0, 1, "name=" + name, "version=" + version).execute().body();
        if (body == null || body.isEmpty()) {
            LOGGER.debug("Can't find any existing process with name '{}' and version '{}'.", name, version);
            return Optional.empty();
        }
        LOGGER.debug("Process '{}' with version '{}' retrieved successfully.", name, version);
        return Optional.of(body.get(0));
    }

    public List<Process> searchProcesses(int page, int count) throws IOException {
        List<Process> body = processAPI.search(page, count).execute().body();
        if (body == null || body.isEmpty()) {
            return Collections.emptyList();
        }
        return body;
    }

    public List<ProcessParameter> searchProcessParameters(int page, int count, long processId) throws IOException {
        List<ProcessParameter> body = processAPI.searchParameters(page, count, "process_id=" + processId).execute()
                .body();
        if (body == null || body.isEmpty()) {
            return Collections.emptyList();
        }
        return body;
    }

    /**
     * @return the id of the case/process instance
     */
    public long startProcess(long processDefinitionId) throws IOException, ClientException {
        return startProcess(processDefinitionId, new HashMap<>());
    }

    /**
     * @return the id of the case/process instance
     */
    public long startProcess(long processDefinitionId, Map<String, Serializable> params)
            throws IOException, ClientException {
        bonitaCookieInterceptor.checkLogged();
        Response<ResponseBody> response = processAPI
                .start(processDefinitionId, restApiConverter.buildJson(params)).execute();
        checkResponse(response);
        HashMap<String, Object> responseMap = objectMapper.readValue(response.body().string(), HashMap.class);
        Number caseId = (Number) responseMap.get("caseId");
        return caseId.longValue();
    }

}
