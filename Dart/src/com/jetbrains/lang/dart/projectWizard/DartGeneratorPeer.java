// Copyright 2000-2018 JetBrains s.r.o.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.jetbrains.lang.dart.projectWizard;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ide.util.projectWizard.SettingsStep;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.platform.WebProjectGenerator;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.ComboboxWithBrowseButton;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.util.ui.AsyncProcessIcon;
import com.intellij.xml.util.XmlStringUtil;
import com.jetbrains.lang.dart.DartBundle;
import com.jetbrains.lang.dart.sdk.DartSdkUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.List;

public class DartGeneratorPeer implements WebProjectGenerator.GeneratorPeer<DartProjectWizardData> {
  private static final String DART_PROJECT_TEMPLATE = "DART_PROJECT_TEMPLATE";
  private static final String CREATE_SAMPLE_UNCHECKED = "CREATE_SAMPLE_UNCHECKED";

  private JPanel myMainPanel;
  private ComboboxWithBrowseButton mySdkPathComboWithBrowse;
  private JBLabel myVersionLabel;

  private JPanel myTemplatesPanel;
  private JPanel myLoadingTemplatesPanel;
  private JPanel myLoadedTemplatesPanel;
  private JBCheckBox myCreateSampleProjectCheckBox;
  private JBList<DartProjectTemplate> myTemplatesList;

  private JBLabel myErrorLabel; // shown in IntelliJ IDEA only

  private boolean myIntellijLiveValidationEnabled = false;

  public DartGeneratorPeer() {
    // set initial values before initDartSdkControls() because listeners should not be triggered on initialization
    mySdkPathComboWithBrowse.getComboBox().setEditable(true);
    //mySdkPathComboWithBrowse.getComboBox().getEditor().setItem(...); initial sdk path will be correctly taken from known paths history

    // now setup controls
    DartSdkUtil.initDartSdkControls(null, mySdkPathComboWithBrowse, myVersionLabel);

    myCreateSampleProjectCheckBox.addActionListener(e -> myTemplatesList.setEnabled(myCreateSampleProjectCheckBox.isSelected()));

    myTemplatesList.setEmptyText(DartBundle.message("set.sdk.to.see.sample.content.options"));

    myErrorLabel.setIcon(AllIcons.Actions.Lightning);
    myErrorLabel.setVisible(false);

    final String sdkPath = mySdkPathComboWithBrowse.getComboBox().getEditor().getItem().toString().trim();
    final String message = DartSdkUtil.getErrorMessageIfWrongSdkRootPath(sdkPath);
    if (message == null) {
      startLoadingTemplates();
    }
    else {
      myLoadingTemplatesPanel.setVisible(false);

      myCreateSampleProjectCheckBox.setEnabled(false);
      myTemplatesList.setEnabled(false);

      final JTextComponent editorComponent = (JTextComponent)mySdkPathComboWithBrowse.getComboBox().getEditor().getEditorComponent();
      editorComponent.getDocument().addDocumentListener(new DocumentAdapter() {
        @Override
        protected void textChanged(final DocumentEvent e) {
          final String sdkPath = mySdkPathComboWithBrowse.getComboBox().getEditor().getItem().toString().trim();
          final String message = DartSdkUtil.getErrorMessageIfWrongSdkRootPath(sdkPath);
          if (message == null) {
            editorComponent.getDocument().removeDocumentListener(this);
            startLoadingTemplates();
          }
        }
      });
    }
  }

  private void startLoadingTemplates() {
    myLoadingTemplatesPanel.setVisible(true);
    myLoadingTemplatesPanel.setPreferredSize(myLoadedTemplatesPanel.getPreferredSize());

    myLoadedTemplatesPanel.setVisible(false);

    myCreateSampleProjectCheckBox.setSelected(false); // until loaded

    final AsyncProcessIcon asyncProcessIcon = new AsyncProcessIcon("Dart project templates loading");
    myLoadingTemplatesPanel.add(asyncProcessIcon, new GridConstraints());  // defaults are ok: row = 0, column = 0
    asyncProcessIcon.resume();

    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      final String sdkPath = mySdkPathComboWithBrowse.getComboBox().getEditor().getItem().toString().trim();
      DartProjectTemplate.loadTemplatesAsync(sdkPath, templates -> {
        asyncProcessIcon.suspend();
        Disposer.dispose(asyncProcessIcon);
        onTemplatesLoaded(templates);
      });
    });
  }

  private void onTemplatesLoaded(final List<DartProjectTemplate> templates) {
    myLoadingTemplatesPanel.setVisible(false);
    myLoadedTemplatesPanel.setVisible(true);
    myCreateSampleProjectCheckBox.setEnabled(true);

    final String selectedTemplateName = PropertiesComponent.getInstance().getValue(DART_PROJECT_TEMPLATE);
    myCreateSampleProjectCheckBox.setSelected(!CREATE_SAMPLE_UNCHECKED.equals(selectedTemplateName));

    myTemplatesList.setVisibleRowCount(Math.min(8, templates.size()));
    myTemplatesList.setEnabled(myCreateSampleProjectCheckBox.isSelected());

    DartProjectTemplate selectedTemplate = null;

    final DefaultListModel<DartProjectTemplate> model = new DefaultListModel<>();
    for (DartProjectTemplate template : templates) {
      model.addElement(template);

      if (template.getName().equals(selectedTemplateName)) {
        selectedTemplate = template;
      }
    }

    myTemplatesList.setModel(model);

    if (selectedTemplate != null) {
      myTemplatesList.setSelectedValue(selectedTemplate, true);
    }
    else if (templates.size() > 0) {
      myTemplatesList.setSelectedIndex(0);
    }

    myTemplatesList.setCellRenderer(new DefaultListCellRenderer() {
      @Override
      public Component getListCellRendererComponent(JList list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
        final JLabel component = (JLabel)super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        final DartProjectTemplate template = (DartProjectTemplate)value;
        final String text = template.getDescription().isEmpty()
                            ? template.getName()
                            : template.getName() + " - " + StringUtil.decapitalize(template.getDescription());
        component.setText(text);
        return component;
      }
    });
  }

  @NotNull
  @Override
  public JComponent getComponent() {
    return myMainPanel;
  }

  @Override
  public void buildUI(final @NotNull SettingsStep settingsStep) {
    settingsStep.addSettingsField(DartBundle.message("dart.sdk.path.label"), mySdkPathComboWithBrowse);
    settingsStep.addSettingsField(DartBundle.message("version.label"), myVersionLabel);
    settingsStep.addSettingsComponent(myTemplatesPanel);
  }

  @NotNull
  @Override
  public DartProjectWizardData getSettings() {
    final String sdkPath = FileUtil.toSystemIndependentName(mySdkPathComboWithBrowse.getComboBox().getEditor().getItem().toString().trim());
    final DartProjectTemplate template = myCreateSampleProjectCheckBox.isSelected() ? myTemplatesList.getSelectedValue() : null;
    PropertiesComponent.getInstance().setValue(DART_PROJECT_TEMPLATE, template == null ? CREATE_SAMPLE_UNCHECKED : template.getName());

    return new DartProjectWizardData(sdkPath, template);
  }

  @Nullable
  @Override
  public ValidationInfo validate() {
    final String sdkPath = mySdkPathComboWithBrowse.getComboBox().getEditor().getItem().toString().trim();
    final String message = DartSdkUtil.getErrorMessageIfWrongSdkRootPath(sdkPath);
    if (message != null) {
      return new ValidationInfo(message, mySdkPathComboWithBrowse);
    }

    if (myCreateSampleProjectCheckBox.isSelected()) {
      if (myTemplatesList.getSelectedValue() == null) {
        return new ValidationInfo(DartBundle.message("project.template.not.selected"), myCreateSampleProjectCheckBox);
      }
    }

    return null;
  }

  public boolean validateInIntelliJ() {
    final ValidationInfo info = validate();

    if (info == null) {
      myErrorLabel.setVisible(false);
      return true;
    }
    else {
      myErrorLabel.setVisible(true);
      myErrorLabel
        .setText(XmlStringUtil.wrapInHtml("<font color='#" + ColorUtil.toHex(JBColor.RED) + "'><left>" + info.message + "</left></font>"));

      if (!myIntellijLiveValidationEnabled) {
        myIntellijLiveValidationEnabled = true;
        enableIntellijLiveValidation();
      }

      return false;
    }
  }

  private void enableIntellijLiveValidation() {
    final JTextComponent editorComponent = (JTextComponent)mySdkPathComboWithBrowse.getComboBox().getEditor().getEditorComponent();
    editorComponent.getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(final DocumentEvent e) {
        validateInIntelliJ();
      }
    });

    myCreateSampleProjectCheckBox.addActionListener(e -> validateInIntelliJ());

    myTemplatesList.addListSelectionListener(e -> validateInIntelliJ());
  }

  @Override
  public boolean isBackgroundJobRunning() {
    return false;
  }

  @Override
  public void addSettingsStateListener(final @NotNull WebProjectGenerator.SettingsStateListener stateListener) {
    final JTextComponent editorComponent = (JTextComponent)mySdkPathComboWithBrowse.getComboBox().getEditor().getEditorComponent();
    editorComponent.getDocument().addDocumentListener(new DocumentAdapter() {
      protected void textChanged(final DocumentEvent e) {
        stateListener.stateChanged(validate() == null);
      }
    });

    myCreateSampleProjectCheckBox.addActionListener(e -> stateListener.stateChanged(validate() == null));

    myTemplatesList.addListSelectionListener(e -> stateListener.stateChanged(validate() == null));
  }

  private void createUIComponents() {
    mySdkPathComboWithBrowse = new ComboboxWithBrowseButton(new ComboBox<>());
  }
}
