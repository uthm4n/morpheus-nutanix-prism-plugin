package com.morpheusdata.nutanix.prism.plugin.utils

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.apache.http.client.CookieStore
import org.apache.http.client.HttpClient
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpPut
import org.apache.http.client.methods.HttpRequestBase
import org.apache.http.client.utils.URIBuilder
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.conn.ssl.SSLContextBuilder
import org.apache.http.conn.ssl.TrustStrategy
import org.apache.http.conn.ssl.X509HostnameVerifier
import org.apache.http.cookie.Cookie
import org.apache.http.entity.ContentType
import org.apache.http.entity.InputStreamEntity
import org.apache.http.impl.client.BasicCookieStore
import org.apache.http.impl.client.HttpClients
import org.apache.http.message.BasicNameValuePair

import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import java.security.cert.X509Certificate

@Slf4j
class NutanixPrismComputeUtility {

	static testConnection(HttpApiClient client, Map authConfig) {
		def rtn = [success:false, invalidLogin:false]
		try {
			def listResults = listHostsV2(client, authConfig)
			rtn.success = listResults.success
		} catch(e) {
			log.error("testConnection to ${authConfig.apiUrl}: ${e}")
		}
		return rtn
	}

	static ServiceResponse getImage(HttpApiClient client, Map authConfig, String imageId) {
		log.debug("checkImageId")
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/images/${imageId}", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'GET')
		if(results?.success) {
			return ServiceResponse.success(results.data)
		} else {
			return ServiceResponse.error()
		}
	}

	static ServiceResponse createImage(HttpApiClient client, Map authConfig, String imageName, String imageType, String sourceUri) {
		log.debug("createImage")
		def body = [
				spec: [
				        name: imageName,
						resources: [
						        image_type: imageType,
						        architecture: 'X86_64'
						]
				],
				metadata: [
				        kind: 'image'
				]
		]
		if(sourceUri) {
			body.spec.resources.source_uri = sourceUri
		}
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/images", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, body: body, ignoreSSL: true), 'POST')
		if(results?.success) {
			return ServiceResponse.success(results.data)
		} else {
			return ServiceResponse.error("Error creating image for ${imageName}", null, results.data)
		}
	}

	static ServiceResponse uploadImage(HttpApiClient client, Map authConfig, String imageExternalId, InputStream stream, Long contentLength) {
		log.debug("uploadImage: ${imageExternalId}")
		def imageStream = new BufferedInputStream(stream, 1200)
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/images/${imageExternalId}/file", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type': ContentType.APPLICATION_OCTET_STREAM.toString(), 'Connection': 'Keep-Alive'], contentType: ContentType.APPLICATION_OCTET_STREAM, body: imageStream, contentLength: contentLength, ignoreSSL: true), 'PUT')
		if(results?.success) {
			return ServiceResponse.success()
		} else {
			return ServiceResponse.error()
		}
	}

	static ServiceResponse createVm(HttpApiClient client, Map authConfig, Map runConfig) {
		log.debug("createVM")

		def resources = [
				num_sockets: runConfig.numSockets,
				memory_size_mib: runConfig.maxMemory,
				num_vcpus_per_socket: runConfig.coresPerSocket,
				disk_list: runConfig.diskList,
				nic_list: runConfig.nicList,
		]

		if(runConfig.diskList.size() > 1 || runConfig.uefi) {
			resources['boot_config'] = [boot_device: [disk_address:[adapter_type:runConfig.storageType.toUpperCase(), device_index:0]]]
		}

		if(runConfig.uefi) {
			resources['boot_config'] = resources['boot_config'] ?:[:]
			resources['boot_config']['boot_type'] = "UEFI"
			if(runConfig.secureBoot) {
				resources['machine_type'] = "Q35"
				resources['boot_config']['boot_type'] = "SECURE_BOOT"
			}
		}

		if(runConfig.cloudInitUserData) {
			if(runConfig.isSysprep) {
				resources['guest_customization'] = [
					"sysprep": [
						"unattend_xml": runConfig.cloudInitUserData
					]
				]
			} else {
				resources['guest_customization'] = [
					"cloud_init": [
						"user_data": runConfig.cloudInitUserData
					],
					"is_overridable": true
				]
			}
		}

		def body = [
				spec: [
						name: runConfig.name,
						resources: resources,
						cluster_reference: runConfig.clusterReference
				],
				metadata: [
						kind: 'vm'
				]
		]

		if(runConfig.categories && runConfig.categories instanceof List) {
			body.metadata.use_categories_mapping = true
			body.metadata.categories_mapping = [:]
			runConfig.categories.each { categoryString ->
				def categoryData = categoryString.trim().split(':')
				def key = categoryData[0]
				def value = categoryData[1]
				if(!body.metadata.categories_mapping[key]) {
					body.metadata.categories_mapping[key] = []
				}
				body.metadata.categories_mapping[key] << value
			}
		}

		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/vms", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, body: body, ignoreSSL: true), 'POST')
		if(results?.success) {
			return ServiceResponse.success(results.data)
		} else {
			return ServiceResponse.error("Error creating vm for ${runConfig.name}", null, results.data)
		}
	}

	static ServiceResponse cloneVm(HttpApiClient client, Map authConfig, Map runConfig, String vmUuid) {
		log.debug("cloneVm")

		def body = [
			override_spec: [
				name: runConfig.name,
				num_sockets: runConfig.numSockets,
				memory_size_mib: runConfig.maxMemory,
				num_vcpus_per_socket: runConfig.coresPerSocket,
				nic_list: runConfig.nicList
			]
		]

		if(runConfig.cloudInitUserData) {
			body['override_spec']['guest_customization'] = [
				"cloud_init": [
					"user_data": runConfig.cloudInitUserData
				],
				"is_overridable": true
			]
		}

		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/vms/${vmUuid}/clone", authConfig.username, authConfig.password,
			new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, body: body, ignoreSSL: true), 'POST')
		if(results?.success) {
			return ServiceResponse.success(results.data)
		} else {
			return ServiceResponse.error("Error cloning vm for ${runConfig.name}", null, results.data)
		}
	}

	static ServiceResponse cloneSnapshot(HttpApiClient client, Map authConfig, Map runConfig, String snapshotUuid) {
		log.debug("cloneSnapshot")

		def clusterUuid = runConfig.clusterReference?.uuid

		def body = [
			spec_list: [
				[
					name: runConfig.name,
					num_vcpus: runConfig.numSockets,
					memory_mb: runConfig.maxMemory,
					num_cores_per_vcpu: runConfig.coresPerSocket,
					override_network_config: false
				]
			],
			vm_customization_config: [
			   userdata: runConfig.cloudInitUserData,
			   fresh_install: false
			]

		]


		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.v2basePath}/snapshots/${snapshotUuid}/clone", authConfig.username, authConfig.password,
			new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, queryParams: [proxyClusterUuid:clusterUuid], body: body, ignoreSSL: true), 'POST')
		if(results?.success) {
			return ServiceResponse.success(results.data)
		} else {
			return ServiceResponse.error("Error cloning snapshot vm for ${runConfig.name}", null, results.data)
		}
	}


	static ServiceResponse getTask(HttpApiClient client, Map authConfig, String uuid) {
		log.debug("getTask")
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/tasks/${uuid}", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'GET')
		if(results?.success) {
			return ServiceResponse.success(results.data)
		} else {
			return ServiceResponse.error("Error getting task ${uuid}", null, results.data)
		}
	}

	static ServiceResponse getVm(HttpApiClient client, Map authConfig, String uuid) {
		log.debug("getVm")
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/vms/${uuid}", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'GET')
		if(results?.success) {
			return ServiceResponse.success(results.data)
		} else {
			return ServiceResponse.error("Error getting vm ${uuid}", null, results.data)
		}
	}

	static ServiceResponse startVm(HttpApiClient client, Map authConfig, String uuid, Map vmBody) {
		log.debug("startVm")
		if(vmBody?.spec?.resources?.power_state) {
			vmBody.spec.resources.power_state = 'ON'
		}
		return updateVm(client, authConfig, uuid, vmBody)
	}

	static ServiceResponse stopVm(HttpApiClient client, Map authConfig, String uuid, Map vmBody) {
		log.debug("startVm")
		if(vmBody?.spec?.resources?.power_state) {
			vmBody.spec.resources.power_state = 'OFF'
		}
		return updateVm(client, authConfig, uuid, vmBody)
	}

	static adjustVmResources(HttpApiClient client, Map authConfig, String uuid, Map updateConfig, Map vmBody) {

		if(vmBody?.spec?.resources) {
			vmBody?.spec?.resources['num_sockets'] = updateConfig.numSockets
			vmBody?.spec?.resources['memory_size_mib'] = updateConfig.maxMemory
			vmBody?.spec?.resources['num_vcpus_per_socket'] = updateConfig.coresPerSocket
		}
		return updateVm(client, authConfig, uuid, vmBody)
	}

	static ServiceResponse updateVm(HttpApiClient client, Map authConfig, String uuid, Map vmBody) {
		vmBody?.remove('status')
		vmBody?.metadata?.remove('spec_hash')
		log.debug("updateVm")
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/vms/${uuid}", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, body: vmBody, ignoreSSL: true), 'PUT')
		if(results?.success) {
			return ServiceResponse.success(results.data)
		} else {
			return ServiceResponse.error("Error updating vm ${uuid}", null, results.data)
		}
	}

	static ServiceResponse destroyVm(HttpApiClient client, Map authConfig, String uuid) {
		log.debug("updateVm")
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/vms/${uuid}", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'DELETE')
		if(results?.success) {
			return ServiceResponse.success(results.data)
		} else {
			return ServiceResponse.error("Error deleting vm ${uuid}", null, results.data)
		}
	}

	static String getNutanixSession(Map authConfig) {
		URIBuilder uriBuilder = new URIBuilder(authConfig.apiUrl)
		uriBuilder.setPath("api/nutanix/v3/users/info")

		HttpRequestBase request
		request = new HttpGet(uriBuilder.build())
		def cookies = []
		def sessionCookie

		def outboundClient
		def rtn = [success: false]
		try {
			def outboundSslBuilder = new SSLContextBuilder()
			outboundSslBuilder.loadTrustMaterial(null, new TrustStrategy() {
				@Override
				boolean isTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
					return true
				}
			})
			def outboundSocketFactory = new SSLConnectionSocketFactory(outboundSslBuilder.build(), SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER)
			def clientBuilder = HttpClients.custom().setSSLSocketFactory(outboundSocketFactory)
			clientBuilder.setHostnameVerifier(new X509HostnameVerifier() {
				boolean verify(String host, SSLSession sess) { return true }

				void verify(String host, SSLSocket ssl) {}

				void verify(String host, String[] cns, String[] subjectAlts) {}

				void verify(String host, X509Certificate cert) {}
			})

			String creds = authConfig.username + ":" + authConfig.password
			String credHeader = "Basic " + Base64.getEncoder().encodeToString(creds.getBytes())

			outboundClient = clientBuilder.build()
			request.addHeader("Authorization", credHeader)
			request.addHeader('Accept','text/html')


			def responseBody = outboundClient.execute(request)
			if(responseBody.statusLine.statusCode < 400) {
				responseBody.getHeaders('Set-Cookie').each {
					String cookie = it.value.split(';')[0]
					cookies.add(cookie)
					if(cookie.startsWith("NTNX_IGW_SESSION")) {
						sessionCookie = cookie
					}
				}
				if(sessionCookie) {
					return sessionCookie
				}
			} else {
				rtn.success = false
			}
		} catch(e) {
			log.error("getNutanixSession error : ${e}", e)
		} finally {
			outboundClient.close()
		}
		return null
	}

	static ServiceResponse getVMConsoleUrl(Map authConfig, String vmUuid, String clusterUuid) {

		String nutanixSessionCookie = getNutanixSession(authConfig) ?: ""

		URIBuilder uriBuilder = new URIBuilder(authConfig.apiUrl)
		uriBuilder.setPath("PrismGateway/j_spring_security_check")

		HttpRequestBase request
		request = new HttpPost(uriBuilder.build())
		def cookies = []
		def sessionCookie

		def outboundClient
		def rtn = [success: false]
		try {
			def outboundSslBuilder = new SSLContextBuilder()
			outboundSslBuilder.loadTrustMaterial(null, new TrustStrategy() {
				@Override
				boolean isTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
					return true
				}
			})
			def outboundSocketFactory = new SSLConnectionSocketFactory(outboundSslBuilder.build(), SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER)
			def clientBuilder = HttpClients.custom().setSSLSocketFactory(outboundSocketFactory)
			clientBuilder.setHostnameVerifier(new X509HostnameVerifier() {
				boolean verify(String host, SSLSession sess) { return true }

				void verify(String host, SSLSocket ssl) {}

				void verify(String host, String[] cns, String[] subjectAlts) {}

				void verify(String host, X509Certificate cert) {}
			})

			outboundClient = clientBuilder.build()
			HttpEntityEnclosingRequestBase postRequest = (HttpEntityEnclosingRequestBase)request
			def formEntity = new UrlEncodedFormEntity([new BasicNameValuePair('j_username',authConfig.username), new BasicNameValuePair('j_password', authConfig.password)])
			postRequest.setEntity(formEntity)
			postRequest.addHeader('Accept','text/html')


			def responseBody = outboundClient.execute(postRequest)
			if(responseBody.statusLine.statusCode < 400) {
				responseBody.getHeaders('Set-Cookie').each {
					String cookie = it.value.split(';')[0]
					cookies.add(cookie)
					if(cookie.startsWith("JSESSIONID")) {
						sessionCookie = cookie
					}
				}
				if(sessionCookie) {
					def socketURI = new URIBuilder(authConfig.apiUrl)
					socketURI.setScheme("wss")
					socketURI.setPath("/vnc/vm/${vmUuid}/proxy")
					socketURI.setParameter("proxyClusterUuid", clusterUuid)
					return ServiceResponse.success([url: socketURI.build().toString(), headers: ['Cookie':sessionCookie + ";" + nutanixSessionCookie]])
				}
			} else {
				rtn.success = false
			}
		} catch(e) {
			log.error("getVmConsoleError: ${e}", e)
		} finally {
			outboundClient.close()
		}
		return ServiceResponse.error("Error getting console for vm ${vmUuid}", null,null )
	}

	static ServiceResponse listSnapshots(HttpApiClient client, Map authConfig, String clusterUuid) {
		log.debug("listSnapshots")

		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.v2basePath}/snapshots", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, queryParams: [proxyClusterUuid:clusterUuid], ignoreSSL: true), 'GET')

		if(results?.success) {
			return ServiceResponse.success(results?.data?.entities)
		} else {
			return ServiceResponse.error("Error listing snapshots for cluster ${clusterUuid}", null, results.data)
		}
	}


	static ServiceResponse getSnapshot(HttpApiClient client, Map authConfig, String clusterUuid, String snapshotUuid) {
		log.debug("getSnapshot")

		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.v2basePath}/snapshots/${snapshotUuid}", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, queryParams: [proxyClusterUuid:clusterUuid], ignoreSSL: true), 'GET')

		if(results?.success) {
			return ServiceResponse.success(results?.data)
		} else {
			return ServiceResponse.error("Error getting snapshot ${snapshotUuid}", null, results.data)
		}
	}


	static ServiceResponse createSnapshot(HttpApiClient client, Map authConfig, String clusterUuid, String vmUuid, String snapshotName) {
		log.debug("createSnapshot")

		def body = [snapshot_specs:[[vm_uuid:vmUuid, snapshot_name:snapshotName]]]

		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.v2basePath}/snapshots", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, queryParams: [proxyClusterUuid:clusterUuid], body: body, ignoreSSL: true), 'POST')

		if(results?.success) {
			return ServiceResponse.success(results?.data)
		} else {
			return ServiceResponse.error("Error creating snapshot for vm ${vmUuid}", null, results.data)
		}
	}

	static ServiceResponse deleteSnapshot(HttpApiClient client, Map authConfig, String clusterUuid, String snapshotUuid) {
		log.debug("deleteSnapshot")

		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.v2basePath}/snapshots/${snapshotUuid}", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, queryParams: [proxyClusterUuid:clusterUuid], ignoreSSL: true), 'DELETE')
		if(results?.success) {
			return ServiceResponse.success(results?.data)
		} else {
			return ServiceResponse.error("Error deleting snapshot ${snapshotUuid}", null, results.data)
		}
	}

	static ServiceResponse restoreSnapshot(HttpApiClient client, Map authConfig, String clusterUuid, String vmUuid, String snapshotUuid) {
		log.debug("restoreSnapshot")
		def body = [restore_network_configuration: true, snapshot_uuid: snapshotUuid, uuid: vmUuid]
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.v2basePath}/vms/${vmUuid}/restore", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, queryParams: [proxyClusterUuid:clusterUuid], body: body, ignoreSSL: true), 'POST')
		if(results?.success) {
			return ServiceResponse.success(results?.data)
		} else {
			return ServiceResponse.error("Error restoring snapshot ${snapshotUuid}", null, results.data)
		}
	}


	static ServiceResponse listNetworks(HttpApiClient client, Map authConfig) {
		log.debug("listNetworks")
		return callListApi(client, 'subnet', 'subnets/list', authConfig)
	}

	static ServiceResponse listImages(HttpApiClient client, Map authConfig) {
		log.debug("listImages")
		return callListApi(client, 'image', 'images/list', authConfig)
	}

	static ServiceResponse listDisksV2(HttpApiClient client, Map authConfig) {
		log.debug("listDisksV2")
		return callListApiV2(client, 'disks', authConfig)
	}

	static ServiceResponse listDatastores(HttpApiClient client, Map authConfig) {
		log.debug("listVMs")
		def groupMemberAttributes = ['container_name','serial','storage.user_capacity_bytes','cluster','storage.user_free_bytes','state','message','reason']
		return callGroupApi(client, 'storage_container', 'serial', groupMemberAttributes, authConfig)
	}

	static ServiceResponse listCategories(HttpApiClient client, Map authConfig) {
		log.debug("listCategories")
		return callListApi(client, 'category', 'categories/list', authConfig)
	}

	static ServiceResponse listCategoryValues(HttpApiClient client, Map authConfig, String categoryName) {
		log.debug("listCategoryValues")
		return callListApi(client, 'category', "categories/${categoryName}/list", authConfig)
	}

	static ServiceResponse listClusters(HttpApiClient client, Map authConfig) {
		log.debug("listClusters")
		return callListApi(client, 'cluster', 'clusters/list', authConfig)
	}

	static ServiceResponse listVPCs(HttpApiClient client, Map authConfig) {
		log.debug("listVPCs")
		return callListApi(client, 'vpc', 'vpcs/list', authConfig)
	}

	static ServiceResponse listHostsV2(HttpApiClient client, Map authConfig) {
		log.debug("listHostsV2")
		return callListApiV2(client, 'hosts', authConfig)
	}

	static ServiceResponse listVMs(HttpApiClient client, Map authConfig) {
		log.debug("listVMs")
		return callListApi(client, 'vm', 'vms/list', authConfig)
	}

	static ServiceResponse listHostMetrics(HttpApiClient client, Map authConfig, List<String> hostUUIDs) {
		log.debug("listHostMetrics")
		def groupMemberAttributes = ['hypervisor_memory_usage_ppm', 'hypervisor_cpu_usage_ppm']
		def appendToBody = [
				entity_ids: hostUUIDs
		]
		return callGroupApi(client, 'host', 'hypervisor_memory_usage_ppm', groupMemberAttributes, authConfig, appendToBody)
	}

	static ServiceResponse listVMMetrics(HttpApiClient client, Map authConfig, List<String> vmUUIDs) {
		log.debug("listVMMetrics")
		def groupMemberAttributes = ['memory_usage_ppm', 'hypervisor_cpu_usage_ppm', 'controller_user_bytes']
		def appendToBody = [
				entity_ids: vmUUIDs
		]
		return callGroupApi(client, 'mh_vm', 'memory_usage_ppm', groupMemberAttributes, authConfig, appendToBody)
	}

	static getGroupEntityValue(List groupData, attributeName) {
		def values = groupData.find { it.name == attributeName }?.values
		if(values?.size() > 0 ) {
			return values.getAt(0).values?.getAt(0)
		}
		null
	}

	static getDiskName(Map diskData) {
		String fullName = diskData.mount_path ?: diskData.disk_uuid
		def lastSlash = fullName.lastIndexOf('/')
		if(lastSlash > 0) {
			return fullName.substring(lastSlash + 1)
		} else {
			return fullName
		}
	}

	static ServiceResponse cloudInitViaCD(HttpApiClient client, Map authConfig, String vmUuid, String imageUuid, Map vmBody) {
		log.debug("cloudInitViaCD")
		def cdromDisk = vmBody?.spec?.resources?.disk_list?.find { it.device_properties?.device_type == 'CDROM' }

		if(cdromDisk) {
			cdromDisk.data_source_reference = [kind: 'image', uuid: imageUuid]
		} else {
			vmBody?.spec?.resources?.disk_list?.add([
					device_properties: [
							device_type: 'CDROM'
					],
					data_source_reference: [kind: 'image', uuid: imageUuid]
			])
		}

		return updateVm(client, authConfig, vmUuid, vmBody)
	}


	private static ServiceResponse callListApi(HttpApiClient client, String kind, String path, Map authConfig) {
		log.debug("callListApi: kind ${kind}, path: ${path}")
		def rtn = new ServiceResponse(success: false)
		try {
			def hasMore = true
			def maxResults = 250
			rtn.data = []
			def offset = 0
			def attempt = 0
			while(hasMore && attempt < 100) {
				def body = [kind: kind, offset: offset, length: maxResults]
				def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/${path}", authConfig.username, authConfig.password,
						new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], body:body, contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'POST')
				log.debug("callListApi results: ${results.toMap()}")
				if(results?.success && !results?.hasErrors()) {
					rtn.success = true
					def pageResults = results.data

					if(pageResults?.entities?.size() > 0) {
						rtn.data += pageResults.entities
						if(pageResults.metadata.length == null) {
							hasMore = false // For some reason.. the clusters/list path (and possibly others) do not return a metadata.length (so assume we got them all)
						} else {
							hasMore = (pageResults.metadata.offset ?: 0) + pageResults.metadata.length < pageResults.metadata.total_matches
						}
						if(hasMore)
							offset += maxResults
					} else {
						hasMore = false
					}
				} else {
					if(!rtn.success) {
						rtn.msg = results.data.message_list?.collect { it.message }?.join(' ')
					}
					hasMore = false
				}
				attempt++
			}

			return rtn
		} catch(e) {
			log.error "Error in callListApi: ${e}", e
		}
		return rtn
	}

	private static ServiceResponse callGroupApi(HttpApiClient client, String entityType, String sortAttribute, List<String> groupMemberAttributes, Map authConfig, Map appendToBody = [:]) {
		log.debug("callGroupApi: ${entityType}")
		def rtn = new ServiceResponse(success: false)
		try {
			def hasMore = true
			def maxResults = 250
			rtn.data = []
			def offset = 0
			def attempt = 0
			def body = [
					entity_type                : entityType,
					group_offset               : 0,
					group_count                : 1,
					group_member_count         : maxResults,
					group_member_offset        : 0,
					group_member_sort_attribute: sortAttribute,
					group_member_attributes    : groupMemberAttributes.collect { [attribute: it] }
			] + appendToBody

			while(hasMore && attempt < 100) {
				body.group_member_offset = offset
				body.group_member_count = maxResults
				def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/groups", authConfig.username, authConfig.password,
						new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], body:body, contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'POST')
				log.debug("callGroupApi results: ${results.toMap()}")
				if(results?.success && !results?.hasErrors()) {
					rtn.success = true
					def groupResults = results.data.group_results.getAt(0)

					if(groupResults?.entity_results?.size() > 0) {
						rtn.data += groupResults.entity_results
						hasMore = body.group_member_offset + groupResults.entity_results.size() < groupResults.total_entity_count
						if(hasMore)
							offset += maxResults
					} else {
						hasMore = false
					}
				} else {
					if(!rtn.success) {
						rtn.msg = results.data.message_list?.collect { it.message }?.join(' ')
					}
					hasMore = false
				}
				attempt++
			}

			return rtn
		} catch(e) {
			log.error "Error in callGroupApi: ${e}", e
		}
		return rtn
	}

	private static ServiceResponse callListApiV2(HttpApiClient client, String path, Map authConfig) {
		log.debug("callListApiV2: path: ${path}")
		def rtn = new ServiceResponse(success: false)
		try {
			def hasMore = true
			def maxResults = 250
			rtn.data = []
			def page = 1
			def attempt = 0
			while(hasMore && attempt < 100) {
				def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.v2basePath}/${path}", authConfig.username, authConfig.password,
						new HttpApiClient.RequestOptions(
								headers:['Content-Type':'application/json'],
								queryParams:[count: maxResults.toString(), page: page.toString()],
								contentType: ContentType.APPLICATION_JSON,
								ignoreSSL: true
						), 'GET')
				log.debug("callListApiV2 results: ${results.toMap()}")
				if(results?.success && !results?.hasErrors()) {
					rtn.success = true
					def pageResults = results.data

					if(pageResults?.entities?.size() > 0) {
						rtn.data += pageResults.entities
						hasMore = pageResults.metadata.end_index < pageResults.metadata.total_entities
						if(hasMore)
							page +=1
					} else {
						hasMore = false
					}
				} else {
					if(!rtn.success) {
						rtn.msg = results.data.message_list?.collect { it.message }?.join(' ')
					}
					hasMore = false
				}
				attempt++
			}

			return rtn
		} catch(e) {
			log.error "Error in callListApiV2: ${e}", e
		}
		return rtn
	}

	static validateServerConfig(MorpheusContext morpheusContext, apiUrl, username, password, Map opts = [:]) {
		log.debug("validateServerConfig: ${opts}")
		def rtn = [success:false, errors:[]]
		try {
			//template
			if(opts.validateTemplate && !opts.template)
				rtn.errors << [field:'template', msg:'Template is required']
			//network
			if(opts.networkId) {
				// great
			} else if (opts?.networkInterfaces) {
				// JSON (or Map from parseNetworks)
				log.debug("validateServerConfig networkInterfaces: ${opts?.networkInterfaces}")
				opts?.networkInterfaces?.eachWithIndex { nic, index ->
					def networkId = nic.network?.id ?: nic.network.group
					log.debug("network.id: ${networkId}")
					if(!networkId) {
						rtn.errors << [field:'networkInterface', msg:'Network is required']
					}
					if (nic.ipMode == 'static' && !nic.ipAddress) {
						rtn.errors = [field:'networkInterface', msg:'You must enter an ip address']
					}
				}
			} else if (opts?.networkInterface) {
				// UI params
				log.debug("validateServerConfig networkInterface: ${opts.networkInterface}")
				toList(opts?.networkInterface?.network?.id)?.eachWithIndex { networkId, index ->
					log.debug("network.id: ${networkId}")
					if(networkId?.length() < 1) {
						rtn.errors << [field:'networkInterface', msg:'Network is required']
					}
					if (networkInterface[index].ipMode == 'static' && !networkInterface[index].ipAddress) {
						rtn.errors = [field:'networkInterface', msg:'You must enter an ip address']
					}
				}
			} else {
				rtn.errors << [field:'networkId', msg:'Network is required']
			}
			if(opts.nodeCount != null && opts.nodeCount == ''){
				rtn.errors << [field:'config.nodeCount', msg:'Number of Hosts Required']
			}
//
//			def resourcePoolId = opts?.resourcePoolId ?: opts?.config?.resourcePoolId ?: opts.server?.config?.resourcePoolId ?: opts?.resourcePool ?: opts?.config?.vmwareResourcePoolId
//			if(!resourcePoolId) {
//				rtn.errors += [field: 'resourcePoolId', msg: 'Resource Pool is required']
//			}
//
//			// If host is selected, must validate that the cluster or datastore is reachable by the host
//			if(apiUrl && opts.hostId) {
//				// Gather up the datastore ids to validate (ignore 'auto')
//				def dataStoreIds = []
//				opts.volumes?.each { volume ->
//					if(volume?.datastoreId) {
//						dataStoreIds << volume.datastoreId
//					}
//					if (volume?.controllerMountPoint) {
//						def mountConfig = volume?.controllerMountPoint.tokenize(':')
//						// log.debug("mountConfig: ${mountConfig}")
//						// id:busNumber:typeId:unitId
//						// def controllerId = mountConfig[0]
//						// def busNumber = mountConfig[1]
//						Long typeId = mountConfig[2]?.toLong()
//						def unitNumber = mountConfig[3]
//						if(typeId) {
//							StorageControllerType storageType = morpheusContext.storageController.storageControllerType.get(typeId.toInteger()).blockingGet()
//							if (storageType.reservedUnitNumber == unitNumber) {
//								rtn.errors += [field: 'volumes', msg: "the storage controller unit selected is reservered: ${volume?.controllerMountPoint}"]
//							}
//						}
//					}
//				}
//				if(dataStoreIds) {
//					if(!opts.useSsl) {
//						SSLUtility.trustAllHostnames()
//						SSLUtility.trustAllHttpsCertificates()
//					}
//					def serviceInstance = connectionPool.getConnection(apiUrl, username, password)
//					ComputeServer computeServer = morpheusContext.computeServer.get(opts.hostId.toLong()).blockingGet()
//					def host = getManagedObject(serviceInstance, 'HostSystem', computeServer.externalId)
//					log.debug "validateServerConfig host: ${host}"
//					def hostDataStores = host.getDatastores()
//					log.debug "validateServerConfig hostDataStores: ${hostDataStores}"
//					dataStoreIds.each { dataStoreId ->
//						if(opts.datastoreId &&  dataStoreId != 'autoCluster' && dataStoreId == 'auto' ) {
//							com.morpheusdata.model.Datastore datastore
//							morpheusContext.cloud.datastore.listById([opts.datastoreId.toLong()]).blockingSubscribe { datastore = it }
//							if(datastore.type == 'generic') {
//								def datastoreAvailable = hostDataStores.find { it.getMOR().getVal() == datastore.externalId } != null
//								if(!datastoreAvailable)
//									rtn.errors << [field:'volume', msg :"Datastore ${datastore.name} is not accessible to host ${computeServer.name}"]
//							} else if(datastore.type == 'cluster') {
//								def storagePod = getManagedObject(serviceInstance, 'StoragePod', datastore.externalId)
//								storagePod.getChildEntity().each { vmds ->
//									def currentDatastoreExtId = vmds.getMOR().getVal()
//									def datastoreAvailable = hostDataStores.find { it.getMOR().getVal() == currentDatastoreExtId } != null
//									if(!datastoreAvailable) {
//										rtn.errors << [field:'volume', msg :"Datastore ${vmds.getName()} within cluster ${datastore.name} is not accessible to host ${computeServer.name}"]
//									}
//								}
//							}
//						}
//					}
//				}
//			}
			rtn.success = rtn.errors.size() == 0
		} catch(e) {
			log.error "validateServerConfig error: ${e}", e
		}
		return rtn
	}

	static toList(value) {
		[value].flatten()
	}

	static waitForPowerState(HttpApiClient client, Map authConfig, String vmId) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(1000l * 20l)
				def serverDetail = getVm(client, authConfig, vmId)
				log.debug("serverDetail: ${serverDetail}")
				if(!serverDetail.success && serverDetail.data.code == 404 ) {
					pending = false
				}
				def serverResource = serverDetail?.data?.spec?.resources
				if(serverDetail.success == true && serverResource.power_state) {
					rtn.success = true
					rtn.data = serverDetail.data
					rtn.powerState = serverResource.power_state
					pending = false
				}
				attempts ++
				if(attempts > 60)
					pending = false
			}
		} catch(e) {
			log.error("An Exception Has Occurred: ${e.message}",e)
		}
		return rtn
	}

	static checkServerReady(HttpApiClient client, Map authConfig, String vmId) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(1000l * 20l)
				def serverDetail = getVm(client, authConfig, vmId)
				log.debug("serverDetail: ${serverDetail}")
				def serverResource = serverDetail?.data?.spec?.resources
				if(serverDetail.success == true && serverResource.power_state == 'ON' && serverResource.nic_list?.size() > 0 && serverResource.nic_list.collect { it.ip_endpoint_list }.collect {it.ip}.flatten().find{checkIpv4Ip(it)} && serverResource.nic_list.collect {it.mac_address}.flatten().find{NutanixPrismComputeUtility.checkMacAddressIsValid(it)}) {
					rtn.success = true
					rtn.virtualMachine = serverDetail.data
					rtn.ipAddress = serverResource.nic_list.collect { it.ip_endpoint_list }.collect {it.ip}.flatten().find{checkIpv4Ip(it)}
					rtn.macAddress = serverResource.nic_list.collect {it.mac_address}.flatten().find{checkMacAddressIsValid(it)}
					rtn.diskList = serverResource.disk_list
					rtn.name = serverDetail?.data?.spec?.name
					pending = false
				}
				attempts ++
				if(attempts > 60)
					pending = false
			}
		} catch(e) {
			log.error("An Exception Has Occurred: ${e.message}",e)
		}
		return rtn
	}

	static checkTaskReady(HttpApiClient client, Map authConfig, String taskId) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(1000l * 20l)
				def taskDetail = getTask(client, authConfig, taskId)
				log.debug("taskDetail: ${taskDetail}")
				def taskStatus = taskDetail?.data?.status
				if(taskDetail.success == true && taskStatus) {
					if(taskStatus == 'SUCCEEDED') {
						rtn.success = true
						rtn.data = taskDetail.data
						pending = false
					} else if (taskStatus == 'FAILED') {
						rtn.success = false
						rtn.data = taskDetail.data
						pending = false
					}
				}
				attempts ++
				if(attempts > 60)
					pending = false
			}
		} catch(e) {
			log.error("An Exception Has Occurred: ${e.message}",e)
		}
		return rtn
	}

	static checkIpv4Ip(ipAddress) {
		def rtn = false
		if(ipAddress) {
			if(ipAddress.indexOf('.') > 0 && !ipAddress.startsWith('169'))
				rtn = true
		}
		return rtn
	}

	static checkMacAddressIsValid(macAddress) {
		def rtn = false
		if (macAddress) {
			if (macAddress.indexOf(':') < 5 && macAddress.length() != 17 && macAddress.split(':').size() != 6) {
				rtn = false
			}
			def chunks = macAddress.split(':')
			try {
				chunks.each { chunk ->
					if (chunk.length() != 2) {
						throw new NumberFormatException("Mac address is not 6 valid chunks of 2")
					}
					Integer.parseInt(chunk, 16)
				}
			} catch (NumberFormatException e) {
				rtn = false
			}
			rtn = true
		}
		return rtn
	}
}
