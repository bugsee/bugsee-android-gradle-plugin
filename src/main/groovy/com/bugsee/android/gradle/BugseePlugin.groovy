package com.bugsee.android.gradle

import groovy.xml.Namespace

import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.mime.MultipartEntity
import org.apache.http.entity.mime.content.FileBody
import org.apache.http.entity.mime.content.StringBody
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.util.EntityUtils

import org.gradle.api.Plugin
import org.gradle.api.Project

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

                    // Upload the mapping file to Bugsee
                    MultipartEntity mpEntity = new MultipartEntity();
                    mpEntity.addPart("mapping", new FileBody(mappingFile));
                    mpEntity.addPart("app_token", new StringBody(appToken));
//                    mpEntity.addPart("appId", new StringBody(appId));
                    mpEntity.addPart("build", new StringBody(versionCode));
                    mpEntity.addPart("uuid", new StringBody(buildUUID));

                    if (versionName != null) {
                        mpEntity.addPart("version", new StringBody(versionName));
                    }
//                    if (System.properties['bugsee.overwrite']) {
//                        mpEntity.addPart("overwrite", new StringBody("true"));
//                    }

                    HttpPost httpPost = new HttpPost(project.bugsee.endpoint)
                    httpPost.setEntity(mpEntity);

                    HttpClient httpClient = new DefaultHttpClient();
                    HttpResponse response = httpClient.execute(httpPost);

                    if (response.getStatusLine().getStatusCode() != 200) {
                        project.logger.warn("Bugsee upload failed: " + EntityUtils.toString(response.getEntity(), "utf-8"))
                    }
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
