package org.springframework.roo.addon.layers.repository.jpa;

import static org.springframework.roo.model.RooJavaType.ROO_REPOSITORY_JPA;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.osgi.service.component.ComponentContext;
import org.springframework.roo.classpath.PhysicalTypeIdentifier;
import org.springframework.roo.classpath.PhysicalTypeMetadata;
import org.springframework.roo.classpath.customdata.taggers.CustomDataKeyDecorator;
import org.springframework.roo.classpath.details.ItdTypeDetails;
import org.springframework.roo.classpath.details.MemberHoldingTypeDetails;
import org.springframework.roo.classpath.itd.AbstractMemberDiscoveringItdMetadataProvider;
import org.springframework.roo.classpath.itd.ItdTypeDetailsProvidingMetadataItem;
import org.springframework.roo.classpath.layers.LayerTypeMatcher;
import org.springframework.roo.model.JavaSymbolName;
import org.springframework.roo.model.JavaType;
import org.springframework.roo.project.LogicalPath;

/**
 * Implementation of {@link RepositoryJpaMetadataProvider}.
 * 
 * @author Stefan Schmidt
 * @author Andrew Swan
 * @since 1.2.0
 */
@Component(immediate = true)
@Service
public class RepositoryJpaMetadataProviderImpl extends
        AbstractMemberDiscoveringItdMetadataProvider implements
        RepositoryJpaMetadataProvider {

    @Reference private CustomDataKeyDecorator customDataKeyDecorator;
    private final Map<JavaType, String> domainTypeToRepositoryMidMap = new LinkedHashMap<JavaType, String>();
    private final Map<String, JavaType> repositoryMidToDomainTypeMap = new LinkedHashMap<String, JavaType>();

    protected void activate(final ComponentContext context) {
        super.setDependsOnGovernorBeingAClass(false);
        metadataDependencyRegistry.addNotificationListener(this);
        metadataDependencyRegistry.registerDependency(
                PhysicalTypeIdentifier.getMetadataIdentiferType(),
                getProvidesType());
        addMetadataTrigger(ROO_REPOSITORY_JPA);
        registerMatchers();
    }

    @Override
    protected String createLocalIdentifier(final JavaType javaType,
            final LogicalPath path) {
        return RepositoryJpaMetadata.createIdentifier(javaType, path);
    }

    protected void deactivate(final ComponentContext context) {
        metadataDependencyRegistry.removeNotificationListener(this);
        metadataDependencyRegistry.deregisterDependency(
                PhysicalTypeIdentifier.getMetadataIdentiferType(),
                getProvidesType());
        removeMetadataTrigger(ROO_REPOSITORY_JPA);
        customDataKeyDecorator.unregisterMatchers(getClass());
    }

    @Override
    protected String getGovernorPhysicalTypeIdentifier(
            final String metadataIdentificationString) {
        final JavaType javaType = RepositoryJpaMetadata
                .getJavaType(metadataIdentificationString);
        final LogicalPath path = RepositoryJpaMetadata
                .getPath(metadataIdentificationString);
        return PhysicalTypeIdentifier.createIdentifier(javaType, path);
    }

    public String getItdUniquenessFilenameSuffix() {
        return "Jpa_Repository";
    }

    @Override
    protected String getLocalMidToRequest(final ItdTypeDetails itdTypeDetails) {
        // Determine the governor for this ITD, and whether any metadata is even
        // hoping to hear about changes to that JavaType and its ITDs
        final JavaType governor = itdTypeDetails.getName();
        final String localMid = domainTypeToRepositoryMidMap.get(governor);
        if (localMid != null) {
            return localMid;
        }

        final MemberHoldingTypeDetails memberHoldingTypeDetails = typeLocationService
                .getTypeDetails(governor);
        if (memberHoldingTypeDetails != null) {
            for (final JavaType type : memberHoldingTypeDetails
                    .getLayerEntities()) {
                final String localMidType = domainTypeToRepositoryMidMap
                        .get(type);
                if (localMidType != null) {
                    return localMidType;
                }
            }
        }
        return null;
    }

    @Override
    protected ItdTypeDetailsProvidingMetadataItem getMetadata(
            final String metadataIdentificationString,
            final JavaType aspectName,
            final PhysicalTypeMetadata governorPhysicalTypeMetadata,
            final String itdFilename) {
        final RepositoryJpaAnnotationValues annotationValues = new RepositoryJpaAnnotationValues(
                governorPhysicalTypeMetadata);
        final JavaType domainType = annotationValues.getDomainType();
        final JavaType identifierType = persistenceMemberLocator
                .getIdentifierType(domainType);
        if (identifierType == null) {
            return null;
        }

        // Remember that this entity JavaType matches up with this metadata
        // identification string
        // Start by clearing any previous association
        final JavaType oldEntity = repositoryMidToDomainTypeMap
                .get(metadataIdentificationString);
        if (oldEntity != null) {
            domainTypeToRepositoryMidMap.remove(oldEntity);
        }
        domainTypeToRepositoryMidMap.put(domainType,
                metadataIdentificationString);
        repositoryMidToDomainTypeMap.put(metadataIdentificationString,
                domainType);

        return new RepositoryJpaMetadata(metadataIdentificationString,
                aspectName, governorPhysicalTypeMetadata, annotationValues,
                identifierType);
    }

    public String getProvidesType() {
        return RepositoryJpaMetadata.getMetadataIdentiferType();
    }

    @SuppressWarnings("unchecked")
    private void registerMatchers() {
        customDataKeyDecorator.registerMatchers(getClass(),
                new LayerTypeMatcher(ROO_REPOSITORY_JPA, new JavaSymbolName(
                        RooJpaRepository.DOMAIN_TYPE_ATTRIBUTE)));
    }
}
