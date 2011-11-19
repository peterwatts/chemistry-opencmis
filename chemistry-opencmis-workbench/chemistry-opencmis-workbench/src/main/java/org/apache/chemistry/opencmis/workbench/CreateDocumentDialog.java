/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.chemistry.opencmis.workbench;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.text.NumberFormat;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFormattedTextField;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.model.ClientSession;
import org.apache.chemistry.opencmis.workbench.swing.CreateDialog;

public class CreateDocumentDialog extends CreateDialog {

    private static final long serialVersionUID = 1L;

    private JTextField nameField;
    private JComboBox typeBox;
    private JTextField filenameField;
    private JFormattedTextField generateContentSizeField;
    private JComboBox generateContentUnitField;
    private JComboBox versioningStateBox;
    private JCheckBox verifyAfterUploadButton;

    public CreateDocumentDialog(Frame owner, ClientModel model) {
        this(owner, model, null);
    }

    public CreateDocumentDialog(Frame owner, ClientModel model, File file) {
        super(owner, "Create Document", model);
        createGUI(file);
    }

    private void createGUI(File file) {
        final CreateDocumentDialog thisDialog = this;

        nameField = new JTextField(60);
        createRow("Name:", nameField, 0);

        typeBox = new JComboBox(getTypes(BaseTypeId.CMIS_DOCUMENT.value()));
        typeBox.setSelectedIndex(0);
        typeBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                DocumentTypeDefinition type = (DocumentTypeDefinition) ((ObjectTypeItem) typeBox.getSelectedItem())
                        .getObjectType();
                if (type.isVersionable()) {
                    versioningStateBox.setSelectedItem(VersioningState.MAJOR);
                } else {
                    versioningStateBox.setSelectedItem(VersioningState.NONE);
                }
            }
        });

        createRow("Type:", typeBox, 1);

        JPanel filePanel = new JPanel(new BorderLayout());

        filenameField = new JTextField(30);
        filenameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void removeUpdate(DocumentEvent e) {
                adjustGenerateContentComponents();
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                adjustGenerateContentComponents();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }

            private void adjustGenerateContentComponents() {
                if (filenameField.getText().length() == 0) {
                    generateContentSizeField.setEnabled(true);
                    generateContentUnitField.setEnabled(true);
                } else {
                    generateContentSizeField.setEnabled(false);
                    generateContentUnitField.setEnabled(false);
                }
            }
        });

        filePanel.add(filenameField, BorderLayout.CENTER);

        JButton browseButton = new JButton("Browse");
        browseButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                JFileChooser fileChooser = new JFileChooser();
                int chooseResult = fileChooser.showDialog(filenameField, "Select");
                if (chooseResult == JFileChooser.APPROVE_OPTION) {
                    if (fileChooser.getSelectedFile().isFile()) {
                        setFile(fileChooser.getSelectedFile());
                    }
                }
            }
        });
        filePanel.add(browseButton, BorderLayout.LINE_END);

        createRow("File:", filePanel, 2);

        JPanel generateContentPanel = new JPanel();
        generateContentPanel.setLayout(new BoxLayout(generateContentPanel, BoxLayout.X_AXIS));

        generateContentSizeField = new JFormattedTextField(NumberFormat.getIntegerInstance());
        generateContentSizeField.setValue(0L);
        generateContentSizeField.setColumns(8);
        generateContentSizeField.setHorizontalAlignment(JTextField.RIGHT);
        generateContentSizeField.setMaximumSize(generateContentSizeField.getPreferredSize());
        generateContentPanel.add(generateContentSizeField);

        generateContentUnitField = new JComboBox(new String[] { "Bytes", "KiB", "MiB", "GiB" });
        generateContentUnitField.setMaximumSize(new Dimension((int) generateContentUnitField.getPreferredSize()
                .getWidth() + 200, (int) generateContentUnitField.getPreferredSize().getHeight()));
        generateContentPanel.add(generateContentUnitField);

        generateContentPanel.add(Box.createHorizontalGlue());

        createRow("Generate content:", generateContentPanel, 3);

        versioningStateBox = new JComboBox(new Object[] { VersioningState.NONE, VersioningState.MAJOR,
                VersioningState.MINOR, VersioningState.CHECKEDOUT });
        if (((DocumentTypeDefinition) ((ObjectTypeItem) typeBox.getSelectedItem()).getObjectType()).isVersionable()) {
            versioningStateBox.setSelectedItem(VersioningState.MAJOR);
        } else {
            versioningStateBox.setSelectedItem(VersioningState.NONE);
        }
        createRow("Versioning State:", versioningStateBox, 4);

        verifyAfterUploadButton = new JCheckBox("Verify content after upload");
        createRow("", verifyAfterUploadButton, 5);

        JButton createButton = new JButton("Create Document");
        createButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                String name = nameField.getText();
                String type = ((ObjectTypeItem) typeBox.getSelectedItem()).getObjectType().getId();
                String filename = filenameField.getText();

                try {
                    if (filename.length() > 0) {
                        // create a document from a file
                        ObjectId objectId = getClientModel().createDocument(name, type, filename,
                                (VersioningState) versioningStateBox.getSelectedItem());

                        if (verifyAfterUploadButton.isSelected()) {
                            ContentStream contentStream = getClientModel().createContentStream(filename);
                            verifyContentStreams(contentStream, objectId);
                        }
                    } else {
                        // create a document with random data
                        long seed = System.currentTimeMillis();
                        long length = ((Number) generateContentSizeField.getValue()).longValue();
                        if (length < 0) {
                            length = 0;
                        } else {
                            for (int i = 0; i < generateContentUnitField.getSelectedIndex(); i++) {
                                length = length * 1024;
                            }
                        }

                        ObjectId objectId = getClientModel().createDocument(name, type, length, seed,
                                (VersioningState) versioningStateBox.getSelectedItem());

                        if (verifyAfterUploadButton.isSelected()) {
                            ContentStream contentStream = getClientModel().createContentStream("", length, seed);
                            verifyContentStreams(contentStream, objectId);
                        }
                    }

                    thisDialog.setVisible(false);
                    thisDialog.dispose();
                } catch (Exception e) {
                    ClientHelper.showError(null, e);
                } finally {
                    try {
                        getClientModel().reloadFolder();
                    } catch (Exception e) {
                        ClientHelper.showError(null, e);
                    }
                }
            }
        });
        createRow("", createButton, 6);

        if (file != null) {
            setFile(file);
        }

        getRootPane().setDefaultButton(createButton);

        showDialog();
    }

    private void setFile(File file) {
        filenameField.setText(file.getAbsolutePath());
        if (nameField.getText().trim().length() == 0) {
            nameField.setText(file.getName());
        }
    }

    private void verifyContentStreams(ContentStream sourceContentStream, ObjectId objectId) {
        // download content from repository
        ClientSession clientSession = getClientModel().getClientSession();
        Document doc = (Document) clientSession.getSession().getObject(objectId,
                clientSession.getObjectOperationContext());
        ContentStream docContentStream = doc.getContentStream();

        // compare
        if (docContentStream == null) {
            if (sourceContentStream.getLength() == 0) {
                JOptionPane.showMessageDialog(getOwner(), "Source file and document content are both empty.",
                        "Verification successful", JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(getOwner(), "Document has no conent but the source file is not empty!",
                        "Verification failed", JOptionPane.ERROR_MESSAGE);
            }
            return;
        }

        InputStream sourceContent = null;
        InputStream docContent = null;
        try {
            sourceContent = new BufferedInputStream(sourceContentStream.getStream());
            docContent = new BufferedInputStream(docContentStream.getStream());

            int fb = 0;
            int db = 0;
            long pos = 0;
            while (fb > -1 && db > -1) {
                fb = sourceContent.read();
                db = docContent.read();

                if (fb != db) {
                    if (fb == -1) {
                        JOptionPane.showMessageDialog(getOwner(),
                                "The document content is bigger than the source file!", "Verification failed",
                                JOptionPane.ERROR_MESSAGE);
                    } else if (db == -1) {
                        JOptionPane.showMessageDialog(getOwner(),
                                "The source file is bigger than the document content!", "Verification failed",
                                JOptionPane.ERROR_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(getOwner(), "Contents differ at byte " + pos + "!",
                                "Verification failed", JOptionPane.ERROR_MESSAGE);
                    }

                    return;
                }

                pos++;
            }

            JOptionPane.showMessageDialog(getOwner(), "Source file and document content are identical.",
                    "Verification successful", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(getOwner(), "Content test exception: " + e.getMessage(),
                    "Verification failed", JOptionPane.ERROR_MESSAGE);
        } finally {
            try {
                sourceContent.close();
            } catch (Exception e) {
            }
            try {
                while (docContent.read() > -1) {
                }

                docContent.close();
            } catch (Exception e) {
            }
        }
    }
}
