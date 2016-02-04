package com.bugsee.android.gradle

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
import org.apache.http.entity.mime.MultipartEntity
import org.apache.http.entity.mime.content.FileBody
import org.apache.http.entity.mime.content.StringBody
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.message.BasicHeader
import org.apache.http.protocol.HTTP
import org.apache.http.util.EntityUtils

import org.gradle.api.Plugin
import org.gradle.api.Project

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BugseePlugin implements Plugin<Project> {
    private static final String APP_TOKEN_TAG = 'com.bugsee.android.APP_TOKEN'
    private static final String BUILD_UUID_TAG = 'com.bugsee.android.BUILD_UUID'

    void apply(Project project) {
        project.extensions.create("bugsee", BugseePluginExtension)

        project.afterEvaluate {

            // Make sure there's an android configuration
            if (!project.android) {
                throw new IllegalStateException('Must apply \'com.android.application\' or \'com.android.library\' first!')
            }

            project.android.applicationVariants.all { variant ->

                // Only create Bugsee tasks for proguard-enabled variants
                if (variant.getObfuscation() == null) {
                    return
                }

                def variantName = variant.name.capitalize()

                // Create Bugsee pre-proguard task
                def bugseeProguardTask = project.task("createBugsee${variantName}ProguardConfig") << {
                    // Create the Bugsee proguard configuration.
                    def file = project.file("build/intermediates/bugsee/bugsee.pro")
                    file.getParentFile().mkdirs()
                    FileWriter fr = new FileWriter(file.path)
                    fr.write("-keepattributes LineNumberTable,SourceFile\n")
                    fr.close()
                    variant.getBuildType().buildType.proguardFiles(file)

                    // Find the processed manifest for this variant
                    def manifestPath = variant.outputs[0].processManifest.manifestOutputFile

                    def appId = variant.applicationId

                    // Parse the AndroidManifest.xml
                    def ns = new Namespace("http://schemas.android.com/apk/res/android", "android")
                    def xml = new XmlParser().parse(manifestPath)

                    // Uniquely identify the build so that we can identify the proguard file.
                    def buildUUID = UUID.randomUUID().toString()

                    def application = xml.application[0]

                    if (application) {

                        def metaDataTags = application['meta-data']
                        // remove any old BUILD_UUID tags
                        def buildUuidTags = metaDataTags.findAll{
                            it.attributes()[ns.name].equals(BUILD_UUID_TAG)
                        }.each{
                            it.parent().remove(it)
                        }

                        application.appendNode('meta-data', [(ns.name): BUILD_UUID_TAG, (ns.value): buildUUID])

                        def writer = new FileWriter(manifestPath)
                        def printer = new XmlNodePrinter(new PrintWriter(writer))
                        printer.preserveWhitespace = true
                        printer.print(xml)
                    }
                }

                // Create Bugsee post-proguard task
                def bugseeTask = project.task("uploadBugsee${variantName}Mapping") << {
                    // Find the processed manifest for this variant
                    def manifestPath = variant.outputs[0].processManifest.manifestOutputFile

                    def appId = variant.applicationId

                    // Parse the AndroidManifest.xml
                    def ns = new Namespace("http://schemas.android.com/apk/res/android", "android")
                    def xml = new XmlParser().parse(manifestPath)

                    // Uniquely identify the build so that we can identify the proguard file.
                    def buildUUID =  ""
                    // Get the Bugsee API key
                    def appToken
                    if(project.bugsee.appToken != null) {
                        appToken = project.bugsee.appToken
                    } else {
                        def metaDataTags = xml.application['meta-data']
                        def appTokenTags = metaDataTags.findAll{ it.attributes()[ns.name].equals(APP_TOKEN_TAG) }
                        if (appTokenTags.size() == 0) {
                            project.logger.warn("Could not find '$APP_TOKEN_TAG' <meta-data> tag in your AndroidManifest.xml")
                            return
                        }
                        appToken = appTokenTags[0].attributes()[ns.value]

                        def buildUUIDTags = metaDataTags.findAll{ it.attributes()[ns.name].equals(BUILD_UUID_TAG) }
                        if (buildUUIDTags.size() == 0) {
                            project.logger.warn("Could not find '$BUILD_UUID_TAG' <meta-data> tag in your AndroidManifest.xml")
                        } else {
                            buildUUID = buildUUIDTags[0].attributes()[ns.value]
                        }
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

                    // Zip the file
                    def zipTemp = File.createTempFile(buildUUID, 'zip')
                    zipTemp.deleteOnExit()
                    def zos = new ZipOutputStream(new FileOutputStream(zipTemp))
                    zos.putNextEntry(new ZipEntry('mapping.txt'))
                    Files.copy(new FileInputStream(mappingFile), zos)
                    zos.closeEntry()
                    zos.close()

                    // Upload the mapping file to Bugsee
                    String json = JsonOutput.toJson([uuid: buildUUID, version: versionName, build: versionCode]);

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

                    // 2. Upload to presigned URL
                    def jsonSlurper = new JsonSlurper()
                    def responseBody = jsonSlurper.parseText(resEntity.content.text)

                    HttpPut httpPut = new HttpPut(responseBody.endpoint)
                    httpPut.setEntity(new FileEntity(zipTemp));
                    response = httpClient.execute(httpPut);

                    if (response.getStatusLine().getStatusCode() != 200) {
                        project.logger.warn("Bugsee upload failed: " + EntityUtils.toString(response.getEntity(), "utf-8"))
                        return
                    }

                    zipTemp.delete()

                    // 3. Let server know
                    httpClient = new DefaultHttpClient();
                    httpPost = new HttpPost(project.bugsee.endpoint + '/symbols/' + responseBody.symbol_id + '/status')
                    response = httpClient.execute(httpPost);
                }

                // Run Bugseepost-build tasks as part of a build
                project.tasks["package${variantName}"].dependsOn bugseeTask
                bugseeTask.dependsOn project.tasks["proguard${variantName}"]
                project.tasks["process${variantName}Resources"].dependsOn bugseeProguardTask
                bugseeProguardTask.dependsOn project.tasks["process${variantName}Manifest"]
            }
        }
    }
}
