package org.springframework.roo.addon.dbre;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.osgi.service.component.ComponentContext;
import org.springframework.roo.addon.dbre.model.Database;
import org.springframework.roo.addon.dbre.model.DatabaseXmlUtils;
import org.springframework.roo.addon.dbre.model.DbreModelService;
import org.springframework.roo.addon.dbre.model.Schema;
import org.springframework.roo.model.JavaPackage;
import org.springframework.roo.process.manager.FileManager;
import org.springframework.roo.project.FeatureNames;
import org.springframework.roo.project.Path;
import org.springframework.roo.project.PathResolver;
import org.springframework.roo.project.ProjectOperations;
import org.springframework.roo.support.logging.HandlerUtils;
import org.springframework.roo.support.osgi.OSGiUtils;
import org.springframework.roo.support.util.DomUtils;
import org.springframework.roo.support.util.XmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Implementation of {@link DbreOperations}.
 * 
 * @author Alan Stewart
 * @since 1.1
 */
@Component
@Service
public class DbreOperationsImpl implements DbreOperations {

    private static final Logger LOGGER = HandlerUtils
            .getLogger(DbreOperationsImpl.class);

@Reference private DbreModelService dbreModelService;
    @Reference private FileManager fileManager;
    @Reference private PathResolver pathResolver;
    @Reference private ProjectOperations projectOperations;
    private ComponentContext context;

    protected void activate(final ComponentContext context) {
        this.context = context;
    }

    public void displayDatabaseMetadata(final Set<Schema> schemas,
            final File file, final boolean view) {
        Validate.notNull(schemas, "Schemas required");

        // Force it to refresh the database from the actual JDBC connection
        final Database database = dbreModelService.refreshDatabase(schemas,
                view, Collections.<String> emptySet(),
                Collections.<String> emptySet());
        database.setIncludeNonPortableAttributes(true);
        outputSchemaXml(database, schemas, file, true);
    }

    protected Collection<URL> findResources(final String path) {
        // For an OSGi bundle search, we add the root prefix to the given path
        return OSGiUtils.findEntriesByPath(context.getBundleContext(),
                OSGiUtils.ROOT_PATH + path);
    }

    public boolean isDbreInstallationPossible() {
        return projectOperations.isFocusedProjectAvailable()
                && projectOperations
                        .isFeatureInstalledInFocusedModule(FeatureNames.JPA);
    }



    private void outputSchemaXml(final Database database,
            final Set<Schema> schemas, final File file,
            final boolean displayOnly) {
        if (database == null) {
            LOGGER.warning("Cannot obtain database information for schema(s) '"
                    + StringUtils.join(schemas, ",") + "'");
        }
        else if (!database.hasTables()) {
            LOGGER.warning("Schema(s) '"
                    + StringUtils.join(schemas, ",")
                    + "' do not exist or does not have any tables. Note that the schema names of some databases are case-sensitive");
        }
        else {
            try {
                if (displayOnly) {
                    final Document document = DatabaseXmlUtils
                            .getDatabaseDocument(database);
                    final OutputStream outputStream = file != null ? new FileOutputStream(
                            file) : new ByteArrayOutputStream();
                    XmlUtils.writeXml(outputStream, document);
                    LOGGER.info(file != null ? "Database metadata written to file "
                            + file.getAbsolutePath()
                            : outputStream.toString());
                }
                else {
                    dbreModelService.writeDatabase(database);
                }
            }
            catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public void reverseEngineerDatabase(final Set<Schema> schemas,
            final JavaPackage destinationPackage,
            final boolean testAutomatically, final boolean view,
            final Set<String> includeTables, final Set<String> excludeTables,
            final boolean includeNonPortableAttributes,
            final boolean activeRecord, final File tableNameMapper) {

		// Force it to refresh the database from the actual JDBC connection
        final Database database = dbreModelService.refreshDatabase(schemas,
                view, includeTables, excludeTables);

		// Check on tableNameMapper param (File vs String) mcm
		DbreTypeUtils.initAliasMappings((tableNameMapper != null) ? tableNameMapper.getPath() : null, database);

        database.setModuleName(projectOperations.getFocusedModuleName());
        database.setActiveRecord(activeRecord);
        database.setDestinationPackage(destinationPackage);
        database.setIncludeNonPortableAttributes(includeNonPortableAttributes);
        database.setTestAutomatically(testAutomatically);
        outputSchemaXml(database, schemas, null, false);

        // Update the pom.xml to add an exclusion for the DBRE XML file in the
        // maven-war-plugin
        updatePom();

        // Change the persistence.xml file to prevent tables being created and
        // dropped.
        updatePersistenceXml();
    }

    private boolean setPropertyValue(final Element root,
            Element propertyElement, final String name, final String value) {
        boolean changed = false;
        propertyElement = XmlUtils.findFirstElement(
                "/persistence/persistence-unit/properties/property[@name = '"
                        + name + "']", root);
        if (propertyElement != null
                && !propertyElement.getAttribute("value").equals(value)) {
            propertyElement.setAttribute("value", value);
            changed = true;
        }
        return changed;
    }

    private void updatePersistenceXml() {
        final String persistencePath = pathResolver.getFocusedIdentifier(
                Path.SRC_MAIN_RESOURCES, "META-INF/persistence.xml");
        final Document document = XmlUtils.readXml(fileManager
                .getInputStream(persistencePath));
        final Element root = document.getDocumentElement();

        final Element providerElement = XmlUtils
                .findFirstElement(
                        "/persistence/persistence-unit[@transaction-type = 'RESOURCE_LOCAL']/provider",
                        root);
        Validate.notNull(providerElement,
                "/persistence/persistence-unit/provider is null");
        final String provider = providerElement.getTextContent();
        final Element propertyElement = null;
        boolean changed = false;
        if (provider.contains("hibernate")) {
            changed = setPropertyValue(root, propertyElement,
                    "hibernate.hbm2ddl.auto", "validate");
            changed |= setPropertyValue(root, propertyElement,
                    "hibernate.ejb.naming_strategy",
                    "org.hibernate.cfg.DefaultNamingStrategy");
        }
        else if (provider.contains("openjpa")) {
            changed = setPropertyValue(root, propertyElement,
                    "openjpa.jdbc.SynchronizeMappings", "validate");
        }
        else if (provider.contains("eclipse")) {
            changed = setPropertyValue(root, propertyElement,
                    "eclipselink.ddl-generation", "none");
        }
        else if (provider.contains("datanucleus")) {
            changed = setPropertyValue(root, propertyElement,
                    "datanucleus.autoCreateSchema", "false");
            changed |= setPropertyValue(root, propertyElement,
                    "datanucleus.autoCreateTables", "false");
            changed |= setPropertyValue(root, propertyElement,
                    "datanucleus.autoCreateColumns", "false");
            changed |= setPropertyValue(root, propertyElement,
                    "datanucleus.autoCreateConstraints", "false");
            changed |= setPropertyValue(root, propertyElement,
                    "datanucleus.validateTables", "false");
            changed |= setPropertyValue(root, propertyElement,
                    "datanucleus.validateConstraints", "false");
        }
        else {
            throw new IllegalStateException("Persistence provider " + provider
                    + " is not supported");
        }

        if (changed) {
            fileManager.createOrUpdateTextFileIfRequired(persistencePath,
                    XmlUtils.nodeToString(document), false);
        }
    }

    private void updatePom() {
        final String pom = pathResolver.getFocusedIdentifier(Path.ROOT,
                "pom.xml");
        final Document document = XmlUtils.readXml(fileManager
                .getInputStream(pom));
        final Element root = document.getDocumentElement();

        final String warPluginXPath = "/project/build/plugins/plugin[artifactId = 'maven-war-plugin']";
        final Element warPluginElement = XmlUtils.findFirstElement(
                warPluginXPath, root);
        if (warPluginElement == null) {
            // Project may not be a web project, so just exit
            return;
        }
        Element excludeElement = XmlUtils
                .findFirstElement(
                        warPluginXPath
                                + "/configuration/webResources/resource/excludes/exclude[text() = '"
                                + DbreModelService.DBRE_XML + "']", root);
        if (excludeElement != null) {
            // <exclude> element is already there, so just exit
            return;
        }

        // Create the required elements
        final Element configurationElement = DomUtils.createChildIfNotExists(
                "configuration", warPluginElement, document);
        final Element webResourcesElement = DomUtils.createChildIfNotExists(
                "webResources", configurationElement, document);
        final Element resourceElement = DomUtils.createChildIfNotExists(
                "resource", webResourcesElement, document);
        final Element excludesElement = DomUtils.createChildIfNotExists(
                "excludes", resourceElement, document);
        excludeElement = DomUtils.createChildIfNotExists("exclude",
                excludesElement, document);
        final Element directoryElement = DomUtils.createChildIfNotExists(
                "directory", resourceElement, document);

        // Populate them with the required text
        excludeElement.setTextContent(DbreModelService.DBRE_XML);
        directoryElement.setTextContent("src/main/resources");

        // Clean up the XML
        DomUtils.removeTextNodes(warPluginElement);

        // Write out the updated POM
        fileManager.createOrUpdateTextFileIfRequired(pom,
                XmlUtils.nodeToString(document), false);
    }
}