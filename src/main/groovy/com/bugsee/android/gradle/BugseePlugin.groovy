package com.bugsee.android.gradle

import com.android.build.gradle.api.ApplicationVariant
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.xml.Namespace
import org.apache.http.HttpEntity
import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpPut
import org.apache.http.entity.FileEntity
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.message.BasicHeader
import org.apache.http.protocol.HTTP
import org.apache.http.util.EntityUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.slf4j.helpers.BasicMarker

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BugseePlugin implements Plugin<Project> {
    private static final String APP_TOKEN_TAG = 'com.bugsee.android.APP_TOKEN'
    private static final String BUILD_UUID_TAG = 'com.bugsee.android.BUILD_UUID'
    private static final String STRING_RESOURCE_START = "@string/";

    private boolean mDebug;

    void apply(Project project) {
        project.extensions.create("bugsee", BugseePluginExtension)

        def debug = project.extensions.bugsee.debug;
        mDebug = debug;
        if (debug) project.logger.warn("Started Bugsee script");
        project.afterEvaluate {

            if (debug) project.logger.warn("Bugsee script afterEvaluate");
            // Make sure there's an android configuration
            if (!project.android) {
                throw new IllegalStateException('Must apply \'com.android.application\' or \'com.android.library\' first!')
            }

            project.android.applicationVariants.all { variant ->

                try {
                    if (debug) project.logger.warn("Bugsee start for variant " + variant.name);
                    // Only create Bugsee tasks for proguard-enabled variants
                    if (variant.getObfuscation() == null && variant.getMappingFile() == null) {
                        return
                    }

                    if (debug) project.logger.warn("Bugsee variant has obfuscation or mapping");

                    def variantName = variant.name.capitalize()

                    // Create Bugsee pre-proguard task
                    def bugseeManifestTask = project.task("createBugsee${variantName}ProguardConfig") << {
                        executeBugseeManifestAction(project, variant);
                    }

                    // Create Bugsee post-proguard task
                    def bugseeUploadTask = project.task("uploadBugsee${variantName}Mapping") << {
                        executeBugseeUploadTask(project, variant);
                    }

                    def variantOutput = variant.outputs.first()
                    // Make bugseeManifestTask a part of build.
                    bugseeManifestTask.mustRunAfter variantOutput.processManifest
                    variantOutput.processResources.dependsOn bugseeManifestTask
                    // Make bugseeUploadTask a part of build.
                    variant.getAssemble().dependsOn bugseeUploadTask
                    bugseeUploadTask.mustRunAfter variantOutput.packageApplication
                } catch (Exception ex) {
                    project.logger.error(new BasicMarker("Bugsee"), "Build variant handling failed for " + variant.name, ex);
                }
            }
        }
    }

    void executeBugseeManifestAction(Project project, ApplicationVariant variant) {
        // Find the processed manifest for this variant
        def manifestPath = variant.outputs[0].processManifest.manifestOutputFile

        // Parse the AndroidManifest.xml
        def ns = new Namespace("http://schemas.android.com/apk/res/android", "android")
        def xml = new XmlParser().parse(manifestPath)

        // Uniquely identify the build so that we can identify the proguard file.
        def buildUUID = UUID.randomUUID().toString()
        def application = xml.application[0]
        if (application) {

            if (mDebug) project.logger.warn("Bugsee manifestTask has app");
            def metaDataTags = application['meta-data']
            // remove any old BUILD_UUID tags
            def buildUuidTags = metaDataTags.findAll {
                it.attributes()[ns.name].equals(BUILD_UUID_TAG)
            }.each {
                it.parent().remove(it)
            }

            application.appendNode('meta-data', [(ns.name): BUILD_UUID_TAG, (ns.value): buildUUID])

            def writer = new FileWriter(manifestPath)
            def printer = new XmlNodePrinter(new PrintWriter(writer))
            printer.preserveWhitespace = true
            printer.print(xml)
        }
    }

    void executeBugseeUploadTask(Project project, ApplicationVariant variant) {
        // Find the processed manifest for this variant
        def manifestPath = variant.outputs[0].processManifest.manifestOutputFile

        // Parse the AndroidManifest.xml
        def ns = new Namespace("http://schemas.android.com/apk/res/android", "android")
        def xml = new XmlParser().parse(manifestPath)

        // Get the Bugsee API key
        NodeList metaDataTags = xml.application['meta-data']
        String appToken = getAppToken(project, ns, metaDataTags);
        if (appToken == null) {
            project.logger.warn("Could not get appToken.");
            return
        }

        if (mDebug) project.logger.warn("appToken is " + appToken);
        // Uniquely identify the build so that we can identify the proguard file.
        def buildUUID
        def buildUUIDTags = metaDataTags.findAll {
            it.attributes()[ns.name].equals(BUILD_UUID_TAG)
        }
        if (buildUUIDTags.size() == 0) {
            project.logger.warn("Could not find '$BUILD_UUID_TAG' <meta-data> tag in your AndroidManifest.xml")
            return
        } else {
            buildUUID = buildUUIDTags[0].attributes()[ns.value]
        }

        // Get the build version
        def versionName = xml.attributes()[ns.versionName]
        def versionCode = xml.attributes()[ns.versionCode]
        if (versionCode == null) {
            project.logger.warn("Could not find 'android:versionCode' value in your AndroidManifest.xml")
            return
        }

        // Find the Proguard mapping file
        File mappingFile = variant.getMappingFile()

        // If proguard configuration includes -dontobfuscate, the mapping file
        // will not exist (but we also won't need it).
        if (!mappingFile.exists()) {
            return
        }

        if (mDebug) project.logger.warn("Bugsee Upload task step 0 (found mapping file). buildUUID: " + buildUUID);
        // Zip the file
        def zipTemp = File.createTempFile(buildUUID, 'zip')
        zipTemp.deleteOnExit()
        def zos = new ZipOutputStream(new FileOutputStream(zipTemp))
        zos.putNextEntry(new ZipEntry('mapping.txt'))
        Files.copy(new FileInputStream(mappingFile), zos)
        zos.closeEntry()
        zos.close()

        if (mDebug) project.logger.warn("Bugsee Upload task step 1.");
        // Upload the mapping file to Bugsee
        String json = JsonOutput.toJson([uuid: buildUUID, version: versionName, build: versionCode]);
        uploadData(project, zipTemp, json, appToken);
    }

    void uploadData(Project project, File file, String json, String appToken) {
        // 1. Create request, get presigned url
        HttpPost httpPost = new HttpPost(project.bugsee.endpoint + '/apps/' + appToken + '/symbols')
        StringEntity body = new StringEntity(json);
        body.setContentType(new BasicHeader(HTTP.CONTENT_TYPE, "application/json"));
        httpPost.setEntity(body);

        HttpClient httpClient = new DefaultHttpClient();
        HttpResponse response = httpClient.execute(httpPost);

        if (response.getStatusLine().getStatusCode() != 200) {
            project.logger.warn("Bugsee upload failed: " + EntityUtils.toString(response.getEntity(), "utf-8"))
            return
        }

        HttpEntity resEntity = response.getEntity()

        if (resEntity == null) {
            project.logger.warn("Bugsee upload failed: no response from server", "utf-8")
            return
        }

        if (mDebug) project.logger.warn("Bugsee Upload task step 2.");
        // 2. Upload to presigned URL
        def jsonSlurper = new JsonSlurper()
        def responseBody = jsonSlurper.parseText(resEntity.content.text)

        HttpPut httpPut = new HttpPut(responseBody.endpoint)
        httpPut.setEntity(new FileEntity(file));
        response = httpClient.execute(httpPut);

        if (response.getStatusLine().getStatusCode() != 200) {
            project.logger.warn("Bugsee upload failed: " + EntityUtils.toString(response.getEntity(), "utf-8"))
            return
        }

        file.delete()
        if (mDebug) project.logger.warn("Bugsee Upload task finish.");
    }

    String getAppToken(Project project, Namespace androidNamespace, NodeList appMetaData) {
        if (project.bugsee.appToken) { // This check is equivalent to (value != null && value != "")
            return project.bugsee.appToken
        } else {
            def appTokenTags = appMetaData.findAll {
                it.attributes()[androidNamespace.name].equals(APP_TOKEN_TAG)
            }
            if (appTokenTags.size() == 0) {
                project.logger.warn("Could not find '$APP_TOKEN_TAG' <meta-data> tag in your AndroidManifest.xml")
                return null
            }
            def appToken = appTokenTags[0].attributes()[androidNamespace.value]
            if (!appToken) {
                project.logger.warn("App token is null.");
                return null;
            }

            if (appToken.startsWith(STRING_RESOURCE_START))
                return getStringResource(project, appToken)

            return appToken;
        }
    }

    String getStringResource(Project project, String resourceIdString) {
        String resourceId = resourceIdString.substring(STRING_RESOURCE_START.length());
        if (!resourceId) {
            project.logger.warn("Invalid string resource name specified: " + resourceIdString);
            return null;
        }

        def stringResourceFiles = project.android.sourceSets.main.res.sourceFiles.findAll { it.name.equals 'strings.xml' }
        if (mDebug) project.logger.warn("resourceId: " + resourceId);

        for (int i = 0; i < stringResourceFiles.size(); i++) {
            def currentXml = new XmlSlurper().parse(stringResourceFiles.get(i))
            def value = currentXml.string.find { resourceId.equals(it.attributes()['name']) }
            if (value)
                return value.text();
        }

        project.logger.warn("Could not find " + resourceIdString + " string resource");
        return null;
    }
}
