package cn.shengwang.convoai.quickstart.tools

import java.util.Base64

/**
 * Utility for generating HTTP Basic Authentication headers
 * 
 * This utility is used to create Authorization headers for Agora RESTful API requests.
 * It follows the HTTP Basic Authentication standard (RFC 7617).
 * 
 * Reference: https://doc.shengwang.cn/doc/convoai/restful/user-guides/http-basic-auth
 */
object Base64Encoding {

    /**
     * Generate HTTP Basic Authentication header from REST Key and REST Secret
     * 
     * This method creates an Authorization header in the format required by Agora RESTful API.
     * The credentials are encoded using Base64 encoding as per HTTP Basic Authentication standard.
     * 
     * @param customerKey REST API Key (also known as customer key)
     * @param customerSecret REST API Secret (also known as customer secret)
     * @return Authorization header string in format "Basic <base64_encoded_credentials>"
     * 
     * @see <a href="https://doc.shengwang.cn/doc/convoai/restful/user-guides/http-basic-auth">Agora RESTful API Basic Auth Documentation</a>
     */
    fun gen(customerKey: String, customerSecret: String): String {
        // Concatenate key and secret with colon separator (format: "key:secret")
        val plainCredentials = "$customerKey:$customerSecret"
        
        // Encode credentials using Base64 encoding
        val base64Credentials = String(Base64.getEncoder().encode(plainCredentials.toByteArray()))

        // Return HTTP Basic Authentication header
        return "Basic $base64Credentials"
    }
}