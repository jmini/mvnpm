package io.mvnpm.file.metadata;

import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.logging.Log;
import java.io.IOException;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;
import io.mvnpm.Constants;
import io.mvnpm.file.FileUtil;
import io.mvnpm.npm.NpmRegistryFacade;
import io.mvnpm.npm.model.Name;
import io.mvnpm.npm.model.Project;
import io.mvnpm.version.InvalidVersionException;
import io.mvnpm.version.Version;
import io.quarkus.cache.CacheResult;
import java.io.UncheckedIOException;

/**
 * Creates a maven-metadata.xml from the NPM Project
 * @author Phillip Kruger (phillip.kruger@gmail.com)
 */
@ApplicationScoped
public class MetadataClient {
    private final MetadataXpp3Writer metadataXpp3Writer = new MetadataXpp3Writer();
    
    @Inject
    NpmRegistryFacade npmRegistryFacade;
    
    @Inject 
    @CacheName("metadata-cache")
    Cache cache;
    
    @CacheResult(cacheName = "metadata-cache")
    public MetadataAndHash getMetadataAndHash(Name name){
        Metadata metadata = getMetadata(name);
        
        try(StringWriter sw = new StringWriter()){
            metadataXpp3Writer.write(sw, metadata);
            byte[] value = sw.toString().getBytes();
            String sha1 = FileUtil.getSha1(value);
            String md5 = FileUtil.getMd5(value);
            MetadataAndHash mah = new MetadataAndHash(sha1, md5, null, value);
            return mah;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
    
    private Metadata getMetadata(Name name){
        Metadata metadata = new Metadata();
        metadata.setGroupId(name.mvnGroupId());
        metadata.setArtifactId(name.mvnArtifactId());
        metadata.setVersioning(getVersioning(name));
        return metadata;
        
    }
    
    public Versioning getVersioning(Name name) {
        Project project = npmRegistryFacade.getProject(name.npmFullName());
        Versioning versioning = new Versioning();
        String latest = getLatest(project);
        versioning.setLatest(latest);
        versioning.setRelease(latest);

        for(String version:project.versions()){
            try {
                Version v = Version.fromString(version);
                if(!versioning.getVersions().contains(v.toString())){
                    // Ignore pre release
                    if(v.qualifier()==null){
                        versioning.addVersion(v.toString());
                    }       
                }
            }catch(InvalidVersionException ive){
                Log.warn("Ignoring version [" + ive.getVersion() + "] for " + name.displayName());
            }
        }

        Map<String, String> time = project.time();
        if(time!=null && time.containsKey(MODIFIED)){
            String dateTime = time.get(MODIFIED);
            // 2022-07-20T09:14:55.450Z
            dateTime = dateTime.replaceAll(Constants.HYPHEN, Constants.EMPTY);
            // 20220720T09:14:55.450Z
            dateTime = dateTime.replaceAll("T", Constants.EMPTY);
            // 2022072009:14:55.450Z
            dateTime = dateTime.replaceAll(Constants.DOUBLE_POINT, Constants.EMPTY);
            // 20220720091455.450Z
            if(dateTime.contains(Constants.DOT)){
                int i = dateTime.indexOf(Constants.DOT);
                dateTime = dateTime.substring(0, i);
            }
            versioning.setLastUpdated(dateTime);
        }else{    
            String timestamp = new SimpleDateFormat(TIME_STAMP_FORMAT).format(new Date());
            versioning.setLastUpdated(timestamp);
        }
        return versioning;
    }
    
    private String getLatest(Project p){
        return p.distTags().latest();
    }
    
    private static final String TIME_STAMP_FORMAT = "yyyyMMddHHmmss";
    private static final String MODIFIED = "modified";
}