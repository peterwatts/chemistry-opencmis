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
package org.apache.chemistry.opencmis.server.impl.atompub;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.PropertyId;
import org.apache.chemistry.opencmis.commons.data.PropertyString;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.impl.Base64;
import org.apache.chemistry.opencmis.commons.impl.XMLConstants;
import org.apache.chemistry.opencmis.commons.impl.XMLConstraints;
import org.apache.chemistry.opencmis.commons.impl.XMLConverter;
import org.apache.chemistry.opencmis.commons.impl.XMLUtils;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.BulkUpdateImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.server.shared.CappedInputStream;
import org.apache.chemistry.opencmis.server.shared.ThresholdOutputStream;
import org.apache.chemistry.opencmis.server.shared.ThresholdOutputStreamFactory;

/**
 * Parser for Atom Entries.
 */
public class AtomEntryParser {

    private static final long MAX_STREAM_LENGTH = 10 * 1024 * 1024;

    private static final String TAG_ENTRY = "entry";
    private static final String TAG_TITLE = "title";
    private static final String TAG_OBJECT = "object";
    private static final String TAG_CONTENT = "content";
    private static final String TAG_BASE64 = "base64";
    private static final String TAG_MEDIATYPE = "mediatype";
    private static final String TAG_FILENAME = "filename";
    private static final String TAG_TYPE = "type";
    private static final String TAG_BULK_UPDATE = "bulkUpdate";

    private static final String ATTR_SRC = "src";
    private static final String ATTR_TYPE = "type";

    protected boolean ignoreAtomContentSrc;

    private CappedInputStream cappedStream;

    private final ThresholdOutputStreamFactory streamFactory;

    private ObjectData object;
    private ContentStreamImpl atomContentStream;
    private ContentStreamImpl cmisContentStream;
    private TypeDefinition typeDef;
    private BulkUpdateImpl bulkUpdate;

    /**
     * Constructor.
     */
    public AtomEntryParser(ThresholdOutputStreamFactory streamFactory) {
        this.streamFactory = streamFactory;
    }

    /**
     * Constructor that immediately parses the given stream.
     */
    public AtomEntryParser(InputStream stream, ThresholdOutputStreamFactory streamFactory) throws Exception {
        this(streamFactory);
        parse(stream);
    }

    /**
     * Sets the flag controlling whether atom content src (external content) is
     * ignored. This flag is false by default (not ignored).
     */
    public void setIgnoreAtomContentSrc(boolean ignoreAtomContentSrc) {
        this.ignoreAtomContentSrc = ignoreAtomContentSrc;
    }

    /**
     * Returns the object.
     */
    public ObjectData getObject() {
        return object;
    }

    /**
     * Returns the properties of the object.
     */
    public Properties getProperties() {
        return (object == null ? null : object.getProperties());
    }

    /**
     * Returns the Id of the object.
     */
    public String getId() {
        Properties properties = getProperties();
        if (properties == null) {
            return null;
        }

        Map<String, PropertyData<?>> propertiesMap = properties.getProperties();
        if (propertiesMap == null) {
            return null;
        }

        PropertyData<?> property = propertiesMap.get(PropertyIds.OBJECT_ID);
        if (property instanceof PropertyId) {
            return ((PropertyId) property).getFirstValue();
        }

        return null;
    }

    /**
     * Returns the ACL of the object.
     */
    public Acl getAcl() {
        return (object == null ? null : object.getAcl());
    }

    /**
     * Returns the policy id list of the object.
     */
    public List<String> getPolicyIds() {
        if ((object == null) || (object.getPolicyIds() == null)) {
            return null;
        }

        return object.getPolicyIds().getPolicyIds();
    }

    /**
     * Returns the content stream.
     */
    public ContentStream getContentStream() {
        return (cmisContentStream == null ? atomContentStream : cmisContentStream);
    }

    /**
     * Returns the type definition.
     */
    public TypeDefinition getTypeDefinition() {
        return typeDef;
    }

    /**
     * Returns the bulk update data.
     */
    public BulkUpdateImpl getBulkUpdate() {
        return bulkUpdate;
    }

    /**
     * Parses the stream.
     */
    public void parse(InputStream stream) throws Exception {
        object = null;
        atomContentStream = null;
        cmisContentStream = null;

        if (stream == null) {
            return;
        }

        cappedStream = new CappedInputStream(stream, MAX_STREAM_LENGTH);
        XMLStreamReader parser = XMLUtils.createParser(cappedStream);

        while (true) {
            int event = parser.getEventType();
            if (event == XMLStreamReader.START_ELEMENT) {
                QName name = parser.getName();

                if (XMLConstants.NAMESPACE_ATOM.equals(name.getNamespaceURI())
                        && (TAG_ENTRY.equals(name.getLocalPart()))) {
                    parseEntry(parser);
                    break;
                } else {
                    throw new CmisInvalidArgumentException("XML is not an Atom entry!");
                }
            }

            if (!XMLUtils.next(parser)) {
                break;
            }
        }

        parser.close();
    }

    /**
     * Parses an Atom entry.
     */
    private void parseEntry(XMLStreamReader parser) throws Exception {
        String atomTitle = null;

        XMLUtils.next(parser);

        // walk through all tags in entry
        while (true) {
            int event = parser.getEventType();
            if (event == XMLStreamReader.START_ELEMENT) {
                QName name = parser.getName();

                if (XMLConstants.NAMESPACE_RESTATOM.equals(name.getNamespaceURI())) {
                    if (TAG_OBJECT.equals(name.getLocalPart())) {
                        parseObject(parser);
                    } else if (TAG_TYPE.equals(name.getLocalPart())) {
                        parseTypeDefinition(parser);
                    } else if (TAG_BULK_UPDATE.equals(name.getLocalPart())) {
                        parseBulkUpdate(parser);
                    } else if (TAG_CONTENT.equals(name.getLocalPart())) {
                        parseCmisContent(parser);
                    } else {
                        XMLUtils.skip(parser);
                    }
                } else if (XMLConstants.NAMESPACE_ATOM.equals(name.getNamespaceURI())) {
                    if (TAG_CONTENT.equals(name.getLocalPart())) {
                        parseAtomContent(parser);
                    } else if (TAG_TITLE.equals(name.getLocalPart())) {
                        atomTitle = XMLUtils.readText(parser, XMLConstraints.MAX_STRING_LENGTH);
                    } else {
                        XMLUtils.skip(parser);
                    }
                } else {
                    XMLUtils.skip(parser);
                }
            } else if (event == XMLStreamReader.END_ELEMENT) {
                break;
            } else {
                if (!XMLUtils.next(parser)) {
                    break;
                }
            }
        }

        // overwrite cmis:name with Atom title
        if ((object != null) && (object.getProperties() != null) && (atomTitle != null) && (atomTitle.length() > 0)) {
            PropertyString nameProperty = new PropertyStringImpl(PropertyIds.NAME, atomTitle);
            ((PropertiesImpl) object.getProperties()).replaceProperty(nameProperty);
        }
    }

    /**
     * Parses a CMIS object.
     */
    private void parseObject(XMLStreamReader parser) throws Exception {
        object = XMLConverter.convertObject(parser);
    }

    /**
     * Parses a CMIS type.
     */
    private void parseTypeDefinition(XMLStreamReader parser) throws Exception {
        typeDef = XMLConverter.convertTypeDefinition(parser);
    }

    /**
     * Parses a bluk update.
     */
    private void parseBulkUpdate(XMLStreamReader parser) throws Exception {
        bulkUpdate = XMLConverter.convertBulkUpdate(parser);
    }

    /**
     * Extract the content stream.
     */
    private void parseAtomContent(XMLStreamReader parser) throws Exception {
        atomContentStream = new ContentStreamImpl();

        // read attributes
        String type = "text";
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            QName attrName = parser.getAttributeName(i);
            if (ATTR_TYPE.equals(attrName.getLocalPart())) {
                atomContentStream.setMimeType(parser.getAttributeValue(i));
                if (parser.getAttributeValue(i) != null) {
                    type = parser.getAttributeValue(i).trim().toLowerCase(Locale.ENGLISH);
                }
            } else if (ATTR_SRC.equals(attrName.getLocalPart())) {
                if (ignoreAtomContentSrc) {
                    atomContentStream = null;
                    XMLUtils.skip(parser);
                    return;
                }
                throw new CmisNotSupportedException("External content not supported!");
            }
        }

        ThresholdOutputStream ths = null;
        byte[] bytes = null;
        if (type.equals("text") || type.equals("html")) {
            ths = readContentBytes(parser);
        } else if (type.equals("xhtml")) {
            bytes = copy(parser);
        } else if (type.endsWith("/xml") || type.endsWith("+xml")) {
            bytes = copy(parser);
        } else if (type.startsWith("text/")) {
            ths = readContentBytes(parser);
        } else {
            ths = readBase64(parser);
        }

        if (ths != null) {
            atomContentStream.setStream(ths.getInputStream());
            atomContentStream.setLength(BigInteger.valueOf(ths.getSize()));
        }

        if (bytes != null) {
            atomContentStream.setStream(new ByteArrayInputStream(bytes));
            atomContentStream.setLength(BigInteger.valueOf(bytes.length));
            cappedStream.deductBytes(bytes.length);
        }
    }

    /**
     * Extract the content stream.
     */
    private void parseCmisContent(XMLStreamReader parser) throws Exception {
        cmisContentStream = new ContentStreamImpl();

        XMLUtils.next(parser);

        // walk through all tags in content
        while (true) {
            int event = parser.getEventType();
            if (event == XMLStreamReader.START_ELEMENT) {
                QName name = parser.getName();

                if (XMLConstants.NAMESPACE_RESTATOM.equals(name.getNamespaceURI())) {
                    if (TAG_MEDIATYPE.equals(name.getLocalPart())) {
                        cmisContentStream.setMimeType(XMLUtils.readText(parser, XMLConstraints.MAX_STRING_LENGTH));
                    } else if (TAG_BASE64.equals(name.getLocalPart())) {
                        ThresholdOutputStream ths = readBase64(parser);
                        cmisContentStream.setStream(ths.getInputStream());
                        cmisContentStream.setLength(BigInteger.valueOf(ths.getSize()));
                    } else {
                        XMLUtils.skip(parser);
                    }
                } else if (XMLConstants.NAMESPACE_APACHE_CHEMISTRY.equals(name.getNamespaceURI())) {
                    if (TAG_FILENAME.equals(name.getLocalPart())) {
                        cmisContentStream.setFileName(XMLUtils.readText(parser, XMLConstraints.MAX_STRING_LENGTH));
                    } else {
                        XMLUtils.skip(parser);
                    }
                } else {
                    XMLUtils.skip(parser);
                }
            } else if (event == XMLStreamReader.END_ELEMENT) {
                break;
            } else {
                if (!XMLUtils.next(parser)) {
                    break;
                }
            }
        }

        XMLUtils.next(parser);
    }

    /**
     * Parses a tag that contains content bytes.
     */
    private ThresholdOutputStream readContentBytes(XMLStreamReader parser) throws Exception {
        ThresholdOutputStream bufferStream = streamFactory.newOutputStream();

        XMLUtils.next(parser);

        try {
            while (true) {
                int event = parser.getEventType();
                if (event == XMLStreamReader.END_ELEMENT) {
                    break;
                } else if (event == XMLStreamReader.CHARACTERS) {
                    String s = parser.getText();
                    if (s != null) {
                        byte[] bytes = s.getBytes("UTF-8");
                        bufferStream.write(bytes);
                        cappedStream.deductBytes(bytes.length);
                    }
                } else if (event == XMLStreamReader.START_ELEMENT) {
                    throw new RuntimeException("Unexpected tag: " + parser.getName());
                }

                if (!XMLUtils.next(parser)) {
                    break;
                }
            }
        } catch (Exception e) {
            bufferStream.destroy(); // remove temp file
            throw e;
        }

        XMLUtils.next(parser);

        return bufferStream;
    }

    /**
     * Parses a tag that contains base64 encoded content.
     */
    private ThresholdOutputStream readBase64(XMLStreamReader parser) throws Exception {
        ThresholdOutputStream bufferStream = streamFactory.newOutputStream();
        @SuppressWarnings("resource")
        Base64.OutputStream b64stream = new Base64.OutputStream(bufferStream, Base64.DECODE);

        XMLUtils.next(parser);

        try {
            while (true) {
                int event = parser.getEventType();
                if (event == XMLStreamReader.END_ELEMENT) {
                    break;
                } else if (event == XMLStreamReader.CHARACTERS) {
                    int len = parser.getTextLength();
                    if (len > 0) {
                        char[] chars = parser.getTextCharacters();
                        int offset = parser.getTextStart();
                        for (int i = 0; i < len; i++) {
                            // it's base64/ASCII
                            b64stream.write(chars[offset + i]);
                        }
                        cappedStream.deductBytes(len);
                    }
                } else if (event == XMLStreamReader.START_ELEMENT) {
                    throw new RuntimeException("Unexpected tag: " + parser.getName());
                }

                if (!XMLUtils.next(parser)) {
                    break;
                }
            }

            b64stream.close();
        } catch (Exception e) {
            bufferStream.destroy(); // remove temp file
            throw e;
        }

        XMLUtils.next(parser);

        return bufferStream;
    }

    /**
     * Copies a subtree into a stream.
     */
    private static byte[] copy(XMLStreamReader parser) throws Exception {
        // create a writer
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLStreamWriter writer = XMLOutputFactory.newInstance().createXMLStreamWriter(out);

        writer.writeStartDocument();

        // copy subtree
        int level = 1;
        while (XMLUtils.next(parser)) {
            int event = parser.getEventType();
            if (event == XMLStreamReader.START_ELEMENT) {
                copyStartElement(parser, writer);
                level++;
            } else if (event == XMLStreamReader.CHARACTERS) {
                writer.writeCharacters(parser.getText());
            } else if (event == XMLStreamReader.COMMENT) {
                writer.writeComment(parser.getText());
            } else if (event == XMLStreamReader.CDATA) {
                writer.writeCData(parser.getText());
            } else if (event == XMLStreamReader.END_ELEMENT) {
                level--;
                if (level == 0) {
                    break;
                }
                writer.writeEndElement();
            } else {
                break;
            }
        }

        writer.writeEndDocument();

        XMLUtils.next(parser);

        return out.toByteArray();
    }

    /**
     * Copies a XML start element.
     */
    private static void copyStartElement(XMLStreamReader parser, XMLStreamWriter writer) throws Exception {
        String namespaceUri = parser.getNamespaceURI();
        String prefix = parser.getPrefix();
        String localName = parser.getLocalName();

        // write start element
        if (namespaceUri != null) {
            if ((prefix == null) || (prefix.length() == 0)) {
                writer.writeStartElement(localName);
            } else {
                writer.writeStartElement(prefix, localName, namespaceUri);
            }
        } else {
            writer.writeStartElement(localName);
        }

        // set namespaces
        for (int i = 0; i < parser.getNamespaceCount(); i++) {
            addNamespace(writer, parser.getNamespacePrefix(i), parser.getNamespaceURI(i));
        }
        addNamespaceIfMissing(writer, prefix, namespaceUri);

        // write attributes
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            String attrNamespaceUri = parser.getAttributeNamespace(i);
            String attrPrefix = parser.getAttributePrefix(i);
            String attrName = parser.getAttributeLocalName(i);
            String attrValue = parser.getAttributeValue(i);

            if ((attrNamespaceUri == null) || (attrNamespaceUri.trim().length() == 0)) {
                writer.writeAttribute(attrName, attrValue);
            } else if ((attrPrefix == null) || (attrPrefix.trim().length() == 0)) {
                writer.writeAttribute(attrNamespaceUri, attrName, attrValue);
            } else {
                addNamespaceIfMissing(writer, attrPrefix, attrNamespaceUri);
                writer.writeAttribute(attrPrefix, attrNamespaceUri, attrName, attrValue);
            }
        }
    }

    /**
     * Checks if the given prefix is assigned to the given namespace.
     */
    @SuppressWarnings("unchecked")
    private static void addNamespaceIfMissing(XMLStreamWriter writer, String prefix, String namespaceUri)
            throws Exception {
        if ((namespaceUri == null) || (namespaceUri.trim().length() == 0)) {
            return;
        }

        if (prefix == null) {
            prefix = "";
        }

        Iterator<String> iter = (Iterator<String>) writer.getNamespaceContext().getPrefixes(namespaceUri);
        if (iter == null) {
            return;
        }

        while (iter.hasNext()) {
            String p = iter.next();
            if ((p != null) && (p.equals(prefix))) {
                return;
            }
        }

        addNamespace(writer, prefix, namespaceUri);
    }

    /**
     * Adds a namespace to a XML element.
     */
    private static void addNamespace(XMLStreamWriter writer, String prefix, String namespaceUri) throws Exception {
        if ((prefix == null) || (prefix.trim().length() == 0)) {
            writer.setDefaultNamespace(namespaceUri);
            writer.writeDefaultNamespace(namespaceUri);
        } else {
            writer.setPrefix(prefix, namespaceUri);
            writer.writeNamespace(prefix, namespaceUri);
        }
    }
}