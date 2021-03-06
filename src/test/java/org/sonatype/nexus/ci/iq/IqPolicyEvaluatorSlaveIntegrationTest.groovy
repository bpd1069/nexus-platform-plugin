/*
 * Copyright (c) 2016-present Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.sonatype.nexus.ci.iq

import org.sonatype.nexus.ci.config.GlobalNexusConfiguration
import org.sonatype.nexus.ci.config.NxiqConfiguration

import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.domains.Domain
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.junit.WireMockRule
import hudson.model.FreeStyleProject
import hudson.model.Slave
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.junit.Rule
import org.jvnet.hudson.test.JenkinsRule
import spock.lang.Specification

import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.givenThat
import static com.github.tomakehurst.wiremock.client.WireMock.okJson
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.put
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig

/**
 * Test builds on slave using WireMock. A HTTP Mock service is required over Spock's GroovyMock as the slave runs
 * under a separate JVM and therefore the IQ client cannot be mocked. IQ client behavior should be tested in its
 * project and these tests should just verify RemoteScanner works on a slave.
 */
class IqPolicyEvaluatorSlaveIntegrationTest
    extends Specification
{
  @Rule
  public JenkinsRule jenkins = new JenkinsRule()

  @Rule
  public WireMockRule wireMockRule = new WireMockRule(wireMockConfig().dynamicPort())

  /**
   * Minimal server mock to allow plugin to succeed
   */
  def configureIqServerMock() {
    WireMock.configureFor("localhost", wireMockRule.port())
    givenThat(get(urlMatching('/rest/config/proprietary\\?.*'))
        .willReturn(okJson('{}')))
    givenThat(put(urlMatching('/rest/ci/scan/.*'))
        .willReturn(okJson('{"scanId":"scanId"}')))
    givenThat(post(urlMatching('/rest/policy/.*'))
        .willReturn(okJson('{}')))
  }

  def configureJenkins() {
    def nxiqConfiguration = [new NxiqConfiguration("http://localhost:${wireMockRule.port()}", 'cred-id')]
    GlobalNexusConfiguration.globalNexusConfiguration.iqConfigs = nxiqConfiguration
    GlobalNexusConfiguration.globalNexusConfiguration.nxrmConfigs = []
    def credentials = new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, 'cred-id', 'name', 'user',
        'password')
    CredentialsProvider.lookupStores(jenkins.jenkins).first().addCredentials(Domain.global(), credentials)
  }

  def 'Should perform a freestyle build on slave'() {
    given: 'a jenkins project'
      FreeStyleProject project = jenkins.createFreeStyleProject()
      project.assignedNode = jenkins.createSlave()
      project.buildersList.add(new IqPolicyEvaluatorBuildStep('stage', 'app', [], [], false, 'cred-id'))
      configureJenkins()

    and: 'a mock IQ server stub'
      configureIqServerMock()

    when: 'the build is scheduled'
      def build = project.scheduleBuild2(0).get()

    then: 'the return code is successful'
      jenkins.assertBuildStatusSuccess(build)
  }

  def 'Should perform a pipeline build on slave'() {
    given: 'a jenkins project'
      WorkflowJob project = jenkins.createProject(WorkflowJob)
      Slave slave = jenkins.createSlave()
      configureJenkins()

    and: 'a mock IQ server stub'
      configureIqServerMock()

    when: 'the build is scheduled'
      project.definition = new CpsFlowDefinition("node ('${slave.getNodeName()}') {\n" +
          "writeFile file: 'dummy.txt', text: 'dummy'\n" +
          "nexusPolicyEvaluation failBuildOnNetworkError: false, iqApplication: 'app', iqStage: 'stage'\n" +
          "}\n")
      def build = project.scheduleBuild2(0).get()

    then: 'the return code is successful'
      jenkins.assertBuildStatusSuccess(build)
  }
}
