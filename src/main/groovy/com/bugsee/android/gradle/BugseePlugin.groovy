package com.bugsee.android.gradle

import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.BaseVariantOutput
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.xml.Namespace
import org.apache.commons.io.FilenameUtils
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
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BugseePlugin implements Plugin<Project> {
    private static final String APP_TOKEN_TAG = 'com.bugsee.android.APP_TOKEN'
    private static final String BUILD_UUID_TAG = 'com.bugsee.android.BUILD_UUID'

    private static final String STRING_RESOURCE_START = "@string/";
    private static final String MIPMAP_RESOURCE_START = "@mipmap/";
    private static final String DRAWABLE_RESOURCE_START = "@drawable/";

    private boolean mDebug;

    void apply(Project project) {
        project.extensions.create("bugsee", BugseePluginExtension)

        def debug = project.extensions.bugsee.debug;
        mDebug = debug;
        if (debug) project.logger.warn("Started Bugsee script");
        project.afterEvaluate {
            // "debug" setting should be initialized here, because client settings are not applied earlier.
            mDebug = project.bugsee.debug;

            if (debug) project.logger.warn("Bugsee script afterEvaluate");
            // Make sure there's an android configuration
            if (!project.android) {
                throw new IllegalStateException('Must apply \'com.android.application\' or \'com.android.library\' first!')
            }
            if (project.android.hasProperty('applicationVariants') && project.android.applicationVariants) {
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
            } else if (project.android.hasProperty('featureVariants') && project.android.featureVariants) {
                project.android.featureVariants.all { variant ->

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
                        bugseeUploadTask.mustRunAfter "transformClassesAndResourcesWithProguardFor${variantName}"
                        project.tasks.findByPath("package${variantName}").dependsOn bugseeUploadTask
                    } catch (Exception ex) {
                        project.logger.error(new BasicMarker("Bugsee"), "Build variant handling failed for " + variant.name, ex);
                    }
                }
            }
        }
    }

    void executeBugseeManifestAction(Project project, BaseVariant variant) {
        if (!variant.outputs || variant.outputs.size() == 0) {
            project.logger.warn("No outputs found for variant " + variant.name)
            return
        }

        if (mDebug) project.logger.warn("Bugsee manifestTask. Processing variant flavor: $variant.flavorName; build type: $variant.buildType.name")
        def buildUUID = UUID.randomUUID().toString()
        // Process all variant outputs. It is necessary when several apks are generated at a time (when "split" block is used).
        for (def output : variant.outputs) {
            // Find the processed manifest for this output
            def manifestFile = getManifestFile(output)

            if (!manifestFile) {
                project.logger.warn("Can't get manifest for variant flavor: $variant.flavorName; build type: $variant.buildType.name; output: $output.name");
                return
            }

            // No split or manifest path is not correct (occurs if manifest file was not deleted after build without split).
            if (manifestFile.parentFile.name.equals(variant.buildType.name)) {
                if (mDebug) project.logger.warn("No split or manifest path is not correct.")
                if (!addBuildUuidToManifest(project, manifestFile, buildUUID)) {
                    project.logger.warn("Application section not found in manifest for variant flavor: $variant.flavorName; build type: $variant.buildType.name; output: $output.name");
                }

                // Iterate nested directories.
                def manifestDirs = manifestFile.parentFile.listFiles(new FileFilter() {
                    @Override
                    boolean accept(File file) {
                        def files = file.list()
                        return (files && files.contains("AndroidManifest.xml"))
                    }
                })

                for (def manifestDir : manifestDirs) {
                    if (!addBuildUuidToManifest(project, new File(FilenameUtils.concat(manifestDir.path, "AndroidManifest.xml")), buildUUID)) {
                        project.logger.warn("Application section not found in manifest for variant flavor: $variant.flavorName; build type: $variant.buildType.name; manifest dir: $manifestDir");
                    }
                }
                break;
            }

            // Normal manifest place for projects with split.
            if (!addBuildUuidToManifest(project, manifestFile, buildUUID)) {
                project.logger.warn("Application section not found in manifest for variant flavor: $variant.flavorName; build type: $variant.buildType.name; output: $output.name");
            }
        }
    }

    boolean addBuildUuidToManifest(Project project, File manifestFile, String buildUuid) {
        // Parse the AndroidManifest.xml
        def ns = new Namespace("http://schemas.android.com/apk/res/android", "android")
        def xml = new XmlParser().parse(manifestFile)

        // Uniquely identify the build so that we can identify the proguard file.
        def application = xml.application[0]
        if (application) {
            if (mDebug) project.logger.warn("Adding buildUUID: $buildUuid to $manifestFile.parentFile.name");
            def metaDataTags = application['meta-data']
            // remove any old BUILD_UUID tags
            def buildUuidTags = metaDataTags.findAll {
                it.attributes()[ns.name].equals(BUILD_UUID_TAG)
            }.each {
                it.parent().remove(it)
            }

            application.appendNode('meta-data', [(ns.name): BUILD_UUID_TAG, (ns.value): buildUuid])

            def writer = new FileWriter(manifestFile)
            def printer = new XmlNodePrinter(new PrintWriter(writer))
            printer.preserveWhitespace = true
            printer.print(xml)
            return true
        }
        return false;
    }

    File getManifestFile(BaseVariantOutput variantOutput) {
        def manifestPath
        try {
            // Android Gradle Plugin < 3.0.0
            manifestPath = variantOutput.processManifest.manifestOutputFile
        } catch (Exception ignored) {
            // Android Gradle Plugin >= 3.0.0
            manifestPath = new File(
                variantOutput.processManifest.manifestOutputDirectory,
                "AndroidManifest.xml")
            if (!manifestPath.isFile()) {
                manifestPath = new File(
                    new File(
                        variantOutput.processManifest.manifestOutputDirectory,
                        variantOutput.dirName),
                    "AndroidManifest.xml")
            }
        }
        return manifestPath;
    }

    void executeBugseeUploadTask(Project project, BaseVariant variant) {
        // Find the processed manifest for this variant
        def manifestPath = getManifestFile(variant.outputs[0])
        if (!manifestPath) {
            project.logger.warn("Can't get manifest for variant flavor: " + variant.flavorName);
            return
        }

        // Parse the AndroidManifest.xml
        def ns = new Namespace("http://schemas.android.com/apk/res/android", "android")
        def xml = new XmlParser().parse(manifestPath)

        // Get the Bugsee API key
        NodeList metaDataTags = xml.application['meta-data']
        String appToken = getAppToken(project, variant, ns, metaDataTags);
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

        File zipTemp = getZipDataToUpload(project, variant, xml, ns, buildUUID);
        if (!zipTemp)
            return

        if (mDebug) project.logger.warn("Bugsee Upload task step 1.");
        String mappingHash = getHash(variant.getMappingFile().text);
        // Upload the mapping file to Bugsee
        String json = JsonOutput.toJson([uuid: buildUUID, version: versionName, build: versionCode, hash: mappingHash]);
        uploadData(project, zipTemp, json, appToken);
    }

    String getHash(String text) {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(text.getBytes("UTF-8"));

        byte[] result = md.digest();
        return String.format("%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x",
            result[0], result[1], result[2], result[3],
            result[4], result[5], result[6], result[7],
            result[8], result[9], result[10], result[11],
            result[12], result[13], result[14], result[15],
            result[16], result[17], result[18], result[19]);
    }

    File getZipDataToUpload(Project project, BaseVariant variant, Node manifestXml, Namespace namespace, String buildUUID) {
        // Find the Proguard mapping file
        File mappingFile = variant.getMappingFile()

        // If proguard configuration includes -dontobfuscate, the mapping file
        // will not exist (but we also won't need it).
        if (!mappingFile.exists()) {
            return null
        }

        if (mDebug) project.logger.warn("Bugsee Upload task step 0 (found mapping file). buildUUID: " + buildUUID);
        // Zip the file
        def zipTemp = File.createTempFile(buildUUID, 'zip')
        zipTemp.deleteOnExit()
        def zos = new ZipOutputStream(new FileOutputStream(zipTemp))
        zos.withStream {
            // Add mapping file
            zos.putNextEntry(new ZipEntry('mapping.txt'))
            def mappingFileFis = new FileInputStream(mappingFile)
            mappingFileFis.withStream { Files.copy(mappingFileFis, zos) }
            zos.closeEntry()
            // Add icon file
            Node application =  manifestXml.application[0]
            if (application) {
                def iconResourceId = application.attribute(namespace.icon);
                if (iconResourceId) {
                    if (mDebug) project.logger.warn("Icon resource id: " + iconResourceId)
                    File icon = getIcon(project, iconResourceId)
                    if (mDebug) project.logger.warn("Chosen icon file: " + icon?.path)
                    if (icon) {
                        def iconFileExtension = FilenameUtils.getExtension(icon.getName());
                        zos.putNextEntry(new ZipEntry('icon.' + iconFileExtension))
                        def iconFis = new FileInputStream(icon)
                        iconFis.withStream { Files.copy(iconFis, zos); }
                        zos.closeEntry()
                    }
                } else {
                    project.logger.warn("Didn't find app icon.")
                }
            }
        }
        return zipTemp
    }

    /**
     *
     * @param project
     * @param file file to upload. It is deleted after uploading.
     * @param json
     * @param appToken
     */
    void uploadData(Project project, File file, String json, String appToken) {
        if (mDebug) project.logger.warn("Starting to send data. Body: $json")
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

        String contentText = resEntity.content.text;
        if (mDebug) project.logger.warn("Bugsee Upload task step 2. Content text: " + contentText);
        def jsonSlurper = new JsonSlurper()
        def responseBody = jsonSlurper.parseText(contentText)

        if (responseBody.code && responseBody.code == 16004) { // This mapping has been already uploaded. Starting from Gradle 3.0 mapping is re-generated only on code changes.
            if (mDebug) project.logger.warn("Got SymbolAlreadyExistsError from server")
            return
        }
        // Check responseBody.endpoint
        if (!responseBody.endpoint) {
            if (responseBody.error) {
                String errorType = responseBody.error.type;
                if ("ApplicationNotFoundError".equals(errorType)) {
                    project.logger.warn("App token is invalid: " + appToken);
                } else {
                    project.logger.warn("Bugsee upload failed with error: " + responseBody.error)
                }
            } else { // No responseBody.error
                project.logger.warn("Bugsee upload failed: null endpoint")
            }
            return
        }

        // 2. Upload to presigned URL
        if (mDebug) project.logger.warn("Endpoint: " + responseBody.endpoint);
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

    String getAppToken(Project project, BaseVariant variant, Namespace androidNamespace, NodeList appMetaData) {
        if (project.bugsee.getAppTokenByVariant()) {
            def variantAppToken = project.bugsee.getAppTokenByVariant()(variant);
            if (variantAppToken) {
                if (mDebug) project.logger.warn("Use appTokenByVariant: " + variantAppToken)
                return variantAppToken
            }
        }
        if (project.bugsee.getDefaultAppToken()) { // This check is equivalent to (value != null && value != "")
            if (mDebug) project.logger.warn("Use project.bugsee.defaultAppToken: " + project.bugsee.getDefaultAppToken())
            return project.bugsee.getDefaultAppToken()
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

    // Tries to get xxhdpi icon, because it has the most suitable size for us (144*144). If xxhdpi icon is not found, get the largest icon.
    File getIcon(Project project, String resourceIdString) {
        String resourceStart;
        if (resourceIdString.startsWith(MIPMAP_RESOURCE_START)) {
            resourceStart = MIPMAP_RESOURCE_START
        } else if (resourceIdString.startsWith(DRAWABLE_RESOURCE_START)) {
            resourceStart = DRAWABLE_RESOURCE_START
        } else return null

        String resourceId = resourceIdString.substring(resourceStart.length())
        if (!resourceId)
            return null

        String resourceFolderType = resourceStart.substring(1, resourceStart.length() - 1);
        if (mDebug) project.logger.warn("Icon resourceFolderType: " + resourceFolderType)

        List<File> iconFiles = project.android.sourceSets.main.res.sourceFiles.findAll {
            // We don't handle case, when specified icon resource has xml type (for example, selector).
            // We get files only from folders of specified type. For example, if specified resource is mipmap, we don't consider drawable with the same name.
            FilenameUtils.getBaseName(it.name).equals(resourceId) && !FilenameUtils.getExtension(it.name).equals('xml') && it.getParent().toLowerCase(Locale.ENGLISH).contains(resourceFolderType)}
        if (!iconFiles || iconFiles.size() == 0)
            return null
        // Try to find xxhdpi icon.
        File xxhdpiFile = iconFiles.find { it.getParent().contains("xxhdpi") };
        if (xxhdpiFile)
            return xxhdpiFile;
        // Get the largest icon.
        iconFiles.sort { left, right -> left.size() <=> right.size() }
        return iconFiles.last()
    }
}
