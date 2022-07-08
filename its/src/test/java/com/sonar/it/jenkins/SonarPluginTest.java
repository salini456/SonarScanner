/*
 * Jenkins :: Integration Tests
 * Copyright (C) 2013-2022 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package com.sonar.it.jenkins;

import static com.sonar.it.jenkins.JenkinsUtils.DEFAULT_SONARQUBE_INSTALLATION;
import static java.util.Objects.requireNonNull;
import static java.util.regex.Matcher.quoteReplacement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

import com.google.common.net.UrlEscapers;
import com.sonar.it.jenkins.JenkinsUtils.FailedExecutionException;
import com.sonar.orchestrator.Orchestrator;
import com.sonar.orchestrator.build.SynchronousAnalyzer;
import com.sonar.orchestrator.container.Server;
import com.sonar.orchestrator.http.HttpMethod;
import com.sonar.orchestrator.locator.FileLocation;
import java.io.File;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import javax.annotation.CheckForNull;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.jenkinsci.test.acceptance.junit.AbstractJUnitTest;
import org.jenkinsci.test.acceptance.junit.Since;
import org.jenkinsci.test.acceptance.junit.WithOS;
import org.jenkinsci.test.acceptance.junit.WithPlugins;
import org.jenkinsci.test.acceptance.po.Build;
import org.jenkinsci.test.acceptance.po.WorkflowJob;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.sonarqube.ws.Components.Component;
import org.sonarqube.ws.Qualitygates;
import org.sonarqube.ws.Webhooks;
import org.sonarqube.ws.client.HttpConnector;
import org.sonarqube.ws.client.HttpException;
import org.sonarqube.ws.client.PostRequest;
import org.sonarqube.ws.client.WsClient;
import org.sonarqube.ws.client.WsClientFactories;
import org.sonarqube.ws.client.components.ShowRequest;
import org.sonarqube.ws.client.qualitygates.CreateConditionRequest;
import org.sonarqube.ws.client.webhooks.CreateRequest;
import org.sonarqube.ws.client.webhooks.DeleteRequest;
import org.sonarqube.ws.client.webhooks.ListRequest;

@WithPlugins({"sonar", "git-server@1.10", "filesystem_scm@2.1", "plain-credentials@1.8"})
public class SonarPluginTest extends AbstractJUnitTest {

  private static final ScannerSupportedVersionProvider SCANNER_VERSION_PROVIDER = new ScannerSupportedVersionProvider();

  private static final String DUMP_ENV_VARS_PIPELINE_CMD = SystemUtils.IS_OS_WINDOWS ? "bat 'set'" : "sh 'env | sort'";
  private static final String SECRET = "very_secret_secret";
  private static final String SONARQUBE_SCANNER_VERSION = "3.3.0.1492";
  private static final String MS_BUILD_RECENT_VERSION = "4.7.1.2311";
  private static final String MVN_PROJECT_KEY = "org.codehaus.sonar-plugins:sonar-abacus-plugin";
  private static String DEFAULT_QUALITY_GATE_NAME;

  private static String EARLIEST_JENKINS_SUPPORTED_MS_BUILD_VERSION;

  @ClassRule
  public static final Orchestrator ORCHESTRATOR = Orchestrator.builderEnv()
    .setSonarVersion(requireNonNull(System.getProperty("sonar.runtimeVersion"), "Please set system property sonar.runtimeVersion"))
    // Disable webhook url validation
    .setServerProperty("sonar.validateWebhooks", Boolean.FALSE.toString())
    .keepBundledPlugins()
    .restoreProfileAtStartup(FileLocation.ofClasspath("/com/sonar/it/jenkins/SonarPluginTest/sonar-way-it-profile_java.xml"))
    .build();

  private static WsClient wsClient;

  private final File csharpFolder = new File("projects", "csharp");
  private final File consoleApp1Folder = new File(csharpFolder, "ConsoleApplication1");
  private final File consoleNetCoreFolder = new File(csharpFolder, "NetCoreConsoleApp");
  private final File jsFolder = new File("projects", "js");

  private JenkinsUtils jenkinsOrch;

  @BeforeClass
  public static void setUpJenkins() {
    wsClient = WsClientFactories.getDefault().newClient(HttpConnector.newBuilder()
      .url(ORCHESTRATOR.getServer().getUrl())
      .credentials(Server.ADMIN_LOGIN, Server.ADMIN_PASSWORD)
      .build());
    DEFAULT_QUALITY_GATE_NAME = getDefaultQualityGateName();

    EARLIEST_JENKINS_SUPPORTED_MS_BUILD_VERSION = SCANNER_VERSION_PROVIDER
        .getEarliestSupportedVersion("sonar-scanner-msbuild");
  }

  @Before
  public void setUp() {
    setDefaultQualityGate(DEFAULT_QUALITY_GATE_NAME);
    jenkinsOrch = new JenkinsUtils(jenkins, driver);
    jenkinsOrch.configureDefaultQG(ORCHESTRATOR);
    jenkins.open();
    enableWebhook();
  }

  @After
  public void cleanup() {
    reset();
    disableGlobalWebhooks();
  }

  @Test
  @WithPlugins("workflow-aggregator@2.7")
  public void env_wrapper_without_params_should_inject_sq_vars() {
    jenkinsOrch.configureSonarInstallation(ORCHESTRATOR);

    String script = "withSonarQubeEnv { " + DUMP_ENV_VARS_PIPELINE_CMD + " }";
    runAndVerifyEnvVarsExist("withSonarQubeEnv-parameterless", script);
  }

  private void runAndVerifyEnvVarsExist(String jobName, String script) {
    String logs = runAndGetLogs(jobName, script);
    verifyEnvVarsExist(logs);
  }

  private String runAndGetLogs(String jobName, String script) {
    createPipelineJobFromScript(jobName, script);
    return jenkinsOrch.executeJob(jobName).getConsole();
  }

  private void verifyEnvVarsExist(String logs) {
    assertThat(logs).contains("SONAR_AUTH_TOKEN=");
    assertThat(logs).contains("SONAR_CONFIG_NAME=" + DEFAULT_SONARQUBE_INSTALLATION);
    assertThat(logs).contains("SONAR_HOST_URL=" + ORCHESTRATOR.getServer().getUrl());
    assertThat(logs).contains("SONAR_MAVEN_GOAL=sonar:sonar");
    assertThat(logs).contains("SONARQUBE_SCANNER_PARAMS={ \"sonar.host.url\" : \"" + StringEscapeUtils.escapeJson(ORCHESTRATOR.getServer().getUrl()) + "");
  }

  private void createPipelineJobFromScript(String jobName, String script) {
    WorkflowJob job = jenkins.jobs.create(WorkflowJob.class, jobName);
    job.script.set("node { withEnv(['MY_SONAR_URL=" + ORCHESTRATOR.getServer().getUrl() + "']) {" + script + "}}");
    job.save();
  }

  private static String getDefaultQualityGateName() {
    Qualitygates.ListWsResponse list = wsClient.qualitygates().list(new org.sonarqube.ws.client.qualitygates.ListRequest());

    return list.getQualitygatesList()
        .stream()
        .filter(Qualitygates.ListWsResponse.QualityGate::getIsDefault)
        .findFirst()
        .orElseGet(() -> list.getQualitygates(0)).getName();
  }

  private void assertSonarUrlOnJob(String jobName, String projectKey) {
      assertThat(jenkinsOrch.getSonarUrlOnJob(jobName)).isEqualTo(ORCHESTRATOR.getServer().getUrl() + "/dashboard?id=" + UrlEscapers.urlFormParameterEscaper().escape(projectKey));
  }

  private static void waitForComputationOnSQServer() {
    new SynchronousAnalyzer(ORCHESTRATOR.getServer()).waitForDone();
  }

  private String enableWebhook() {
    String url = StringUtils.removeEnd(jenkins.getCurrentUrl(), "/") + "/sonarqube-webhook/";
    Webhooks.CreateWsResponse response = wsClient.webhooks().create(new CreateRequest()
      .setName("Jenkins")
      .setUrl(url)
      .setSecret(SECRET)
    );

    return response.getWebhook().getKey();
  }

  public void reset() {
    // We add one day to ensure that today's entries are deleted.
    Instant instant = Instant.now().plus(1, ChronoUnit.DAYS);

    // The expected format is yyyy-MM-dd.
    String currentDateTime = DateTimeFormatter.ISO_LOCAL_DATE
      .withZone(ZoneId.of("UTC"))
      .format(instant);

    ORCHESTRATOR.getServer()
      .newHttpCall("/api/projects/bulk_delete")
      .setAdminCredentials()
      .setMethod(HttpMethod.POST)
      .setParams("analyzedBefore", currentDateTime)
      .execute();
  }

  private static void disableGlobalWebhooks() {
    wsClient.webhooks().list(new ListRequest()).getWebhooksList().forEach(p -> wsClient.webhooks().delete(new DeleteRequest().setWebhook(p.getKey())));
  }

  @CheckForNull
  static Component getProject(String componentKey) {
    try {
      return wsClient.components().show(new ShowRequest().setComponent(componentKey)).getComponent();
    } catch (HttpException e) {
      if (e.code() == 404) {
        return null;
      }
      throw new IllegalStateException(e);
    }
  }

  private void setDefaultQualityGate(String qualityGateName) {
    wsClient.wsConnector().call(
        new PostRequest("api/qualitygates/set_as_default").setParam("name", qualityGateName)
    );
  }

}
