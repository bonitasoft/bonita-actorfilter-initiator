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

import com.bonitasoft.deployer.client.event.ImportNotifier;
import com.bonitasoft.deployer.client.event.ImportWarningEvent;
import com.bonitasoft.deployer.client.exception.UnauthorizedException;
import com.bonitasoft.deployer.client.internal.BonitaCookieInterceptor;
import com.bonitasoft.deployer.client.internal.api.ConfigurationAPI;
import com.github.zafarkhaja.semver.Version;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Response;

@Slf4j
public class ConfigurationService extends ClientService {

    private final BonitaCookieInterceptor bonitaCookieInterceptor;
    private final SystemService systemService;
    private final ConfigurationAPI configurationAPI;
    private final ImportNotifier importNotifier;

    private final Version minBonitaVersionSupportingConfiguration = new Version.Builder("7.8.0").build();

    public ConfigurationService(BonitaCookieInterceptor bonitaCookieInterceptor, SystemService systemService,
            ConfigurationAPI configurationAPI, ImportNotifier importNotifier) {
        this.bonitaCookieInterceptor = bonitaCookieInterceptor;
        this.systemService = systemService;
        this.configurationAPI = configurationAPI;
        this.importNotifier = importNotifier;
    }

    /**
     * Import a Bonita Application Configuration file.<p>
     * This feature has been introduced in Bonita 7.8.0 and is only available on 'Subscription' editions. Skip import
     * for other cases.
     */
    public void importBonitaConfiguration(File configurationFile) throws IOException, UnauthorizedException {
        bonitaCookieInterceptor.checkLogged();

        if (systemService.getLicense().isCommunityEdition()) {
            String message = "Skipping the Bonita Configuration deployment: your current Bonita license is 'Community'"
                    + " and the Bonita Configuration feature is supported only on 'Subscription' editions.";
            importNotifier.post(new ImportWarningEvent(message));
            return;
        }

        Version version = systemService.getVersion();
        if (version.lessThan(minBonitaVersionSupportingConfiguration)) {
            String message = format("Skipping the Bonita Configuration deployment: your current Bonita version is '%s'"
                    + " and the Bonita Configuration feature is supported since Bonita '%s'.", version,
                    minBonitaVersionSupportingConfiguration);
            importNotifier.post(new ImportWarningEvent(message));
            return;
        }

        log.info("Deploying configuration '{}'...", configurationFile.getName());
        RequestBody requestFile = RequestBody.create(MultipartBody.FORM, configurationFile);
        MultipartBody.Part body = MultipartBody.Part.createFormData("configuration",
                configurationFile.getName(),
                requestFile);

        Response<ResponseBody> responseUpload = configurationAPI.deployConfiguration(body).execute();
        checkResponse(responseUpload);

        log.info("Configuration deployed successfully.");
    }

}