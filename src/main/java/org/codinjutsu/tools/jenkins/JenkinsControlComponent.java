/*
 * Copyright (c) 2012 David Boissier
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.codinjutsu.tools.jenkins;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.BalloonBuilder;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.*;
import com.intellij.ui.BrowserHyperlinkListener;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.codinjutsu.tools.jenkins.logic.JenkinsBrowserLogic;
import org.codinjutsu.tools.jenkins.logic.JenkinsRequestManager;
import org.codinjutsu.tools.jenkins.model.Build;
import org.codinjutsu.tools.jenkins.model.Jenkins;
import org.codinjutsu.tools.jenkins.util.GuiUtil;
import org.codinjutsu.tools.jenkins.util.HtmlUtil;
import org.codinjutsu.tools.jenkins.view.*;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

import java.awt.*;
import java.util.concurrent.TimeUnit;

import static org.codinjutsu.tools.jenkins.JenkinsConfiguration.Layout.SINGLE;
import static org.codinjutsu.tools.jenkins.view.action.RunBuildAction.RUN_ICON;

@State(
        name = JenkinsControlComponent.JENKINS_CONTROL_COMPONENT_NAME,
        storages = {@Storage(id = "JenkinsControlSettings", file = "$PROJECT_FILE$")}
)
public class JenkinsControlComponent
        implements ProjectComponent, Configurable, PersistentStateComponent<JenkinsConfiguration> {

    static final String JENKINS_CONTROL_COMPONENT_NAME = "JenkinsControlComponent";
    private static final String JENKINS_CONTROL_PLUGIN_NAME = "Jenkins Control Plugin";

    private static final String JENKINS_BROWSER = "jenkinsBrowser";
    private static final String JENKINS_BROWSER_TITLE = "Jenkins Browser";
    private static final String JENKINS_BROWSER_ICON = "jenkins_logo.png";

    private final JenkinsConfiguration configuration;
    private JenkinsConfigurationPanel configurationPanel;

    private final Project project;
    private JenkinsBrowserLogic jenkinsBrowserLogic;
    private JenkinsRequestManager jenkinsRequestManager;


    public JenkinsControlComponent(Project project) {
        this.project = project;
        this.configuration = new JenkinsConfiguration();
    }


    @NotNull
    public String getComponentName() {
        return JENKINS_CONTROL_COMPONENT_NAME;
    }


    @Nls
    public String getDisplayName() {
        return JENKINS_CONTROL_PLUGIN_NAME;
    }


    public Icon getIcon() {
        return null;
    }


    public JComponent createComponent() {
        if (configurationPanel == null) {
            configurationPanel = new JenkinsConfigurationPanel(jenkinsRequestManager);
        }
        return configurationPanel.getRootPanel();
    }


    public boolean isModified() {
        return configurationPanel != null && configurationPanel.isModified(configuration);
    }


    public void disposeUIResources() {
        configurationPanel = null;
    }


    public JenkinsConfiguration getState() {
        return configuration;
    }


    public void loadState(JenkinsConfiguration jenkinsConfiguration) {
        XmlSerializerUtil.copyBean(jenkinsConfiguration, configuration);
    }


    public void projectOpened() {
        installJenkinsPanel();
    }

    private void installJenkinsPanel() {
        ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
        ToolWindow toolWindow = toolWindowManager.registerToolWindow(JENKINS_BROWSER, true, ToolWindowAnchor.RIGHT);

        jenkinsRequestManager = new JenkinsRequestManager(configuration.getCrumbFile());
        JenkinsPanel jenkinsPanel;

        JenkinsBrowserPanel browserPanel = new JenkinsBrowserPanel();
        RssLatestBuildPanel rssLatestJobPanel = new RssLatestBuildPanel();
        JenkinsBrowserLogic.JobStatusCallback jobStatusCallback;
        if (configuration.getLayout() == SINGLE) {
            jenkinsPanel = JenkinsPanel.onePanel(browserPanel, rssLatestJobPanel);
            jobStatusCallback = new JenkinsBrowserLogic.JobStatusCallback() {
                public void notifyOnBuildFailure(final String jobName, final Build build) {
                    GuiUtil.runInSwingThread(new Runnable() {
                        public void run() {
                            ToolWindowManager.getInstance(project)
                                    .notifyByBalloon(JENKINS_BROWSER,
                                            MessageType.ERROR,
                                            HtmlUtil.createHtmlLinkMessage(jobName + "#" + build.getNumber() + ": FAILED", build.getUrl()),
                                            null,
                                            new BrowserHyperlinkListener());

                        }
                    });
                }
            };

            jenkinsBrowserLogic = new JenkinsBrowserLogic(configuration, jenkinsRequestManager, browserPanel, rssLatestJobPanel, jobStatusCallback);
            jenkinsBrowserLogic.init();

        } else {
            final JenkinsRssWidget jenkinsRssWidget = new JenkinsRssWidget(project, rssLatestJobPanel);

            jenkinsPanel = JenkinsPanel.browserOnly(browserPanel);
            jobStatusCallback = new JenkinsBrowserLogic.JobStatusCallback() {
                public void notifyOnBuildFailure(final String jobName, final Build build) {
                    GuiUtil.runInSwingThread(new Runnable() {
                        public void run() {
                            BalloonBuilder balloonBuilder = JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(jobName + "#" + build.getNumber() + ": FAILED", MessageType.ERROR, null);
                            Balloon balloon = balloonBuilder.setFadeoutTime(TimeUnit.SECONDS.toMillis(1)).createBalloon();
                            balloon.show(new RelativePoint(jenkinsRssWidget.getComponent(), new Point(0, 0)), Balloon.Position.above);
                        }
                    });
                }
            };


            jenkinsBrowserLogic = new JenkinsBrowserLogic(configuration, jenkinsRequestManager, browserPanel, rssLatestJobPanel, jobStatusCallback);

            jenkinsBrowserLogic.installWidgetCallBack(new JenkinsBrowserLogic.ViewLoadCallback() {
                @Override
                public void doAfterLoadingJobs(Jenkins jenkins) {
                    jenkinsRssWidget.updateIcon(jenkins.getTotalBrokenBuilds());
                }
            });
            jenkinsBrowserLogic.init();


            final StatusBar statusBar = WindowManager.getInstance().getStatusBar(project);
            statusBar.addWidget(jenkinsRssWidget);
            jenkinsRssWidget.install(statusBar);
        }


        Content content = ContentFactory.SERVICE.getInstance()
                .createContent(jenkinsPanel, JENKINS_BROWSER_TITLE, false);
        toolWindow.getContentManager().addContent(content);
        toolWindow.setIcon(GuiUtil.loadIcon(JENKINS_BROWSER_ICON));
    }


    public void projectClosed() {
        ToolWindowManager.getInstance(project).unregisterToolWindow(JENKINS_BROWSER);
    }


    public String getHelpTopic() {
        return null;
    }


    public void apply() throws ConfigurationException {
        if (configurationPanel != null) {
            try {
                boolean layoutModified = configurationPanel.isLayoutModified(configuration);
                configurationPanel.applyConfigurationData(configuration);
                if (layoutModified) {
                    JOptionPane.showMessageDialog(JOptionPane.getRootFrame(), "You need to restart your IDE to apply the new UI Layout.", "UI Layout Modification", JOptionPane.INFORMATION_MESSAGE);
                }

                jenkinsBrowserLogic.reloadConfiguration();

            } catch (org.codinjutsu.tools.jenkins.exception.ConfigurationException ex) {
                throw new ConfigurationException(ex.getMessage());
            }
        }
    }


    public void reset() {
        configurationPanel.loadConfigurationData(configuration);
    }


    public void notifyInfoJenkinsToolWindow(final String message) {
        ToolWindowManager.getInstance(project).notifyByBalloon(
                JENKINS_BROWSER,
                MessageType.INFO,
                message,
                RUN_ICON,
                new BrowserHyperlinkListener());
    }

    public void notifyErrorJenkinsToolWindow(final String message) {
        ToolWindowManager.getInstance(project).notifyByBalloon(JENKINS_BROWSER, MessageType.ERROR, message);
    }


    public void initComponent() {

    }


    public void disposeComponent() {
    }
}
