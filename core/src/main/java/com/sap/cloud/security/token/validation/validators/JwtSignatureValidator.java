package com.sap.cloud.security.token.validation.validators;

import static com.sap.cloud.security.core.Assertions.*;
import static com.sap.cloud.security.xsuaa.jwk.JsonWebKey.*;
import static com.sap.cloud.security.xsuaa.jwk.JsonWebKeyConstants.*;
import static java.nio.charset.StandardCharsets.*;

import javax.annotation.Nullable;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.sap.cloud.security.token.Token;
import com.sap.cloud.security.token.validation.ValidationResult;
import com.sap.cloud.security.token.validation.ValidationResults;
import com.sap.cloud.security.token.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sap.cloud.security.xsuaa.client.TokenKeyServiceWithCache;

public class JwtSignatureValidator implements Validator<Token> {
	private TokenKeyServiceWithCache tokenKeyService;
	private static Logger LOGGER = LoggerFactory.getLogger(JwtSignatureValidator.class);
	private final static Map<String, Type> MAP_ALGORITHM_TYPE;
	static {
		MAP_ALGORITHM_TYPE = new HashMap<>();
		MAP_ALGORITHM_TYPE.put("RS256", Type.RSA);
		MAP_ALGORITHM_TYPE.put("ES256", Type.EC);
	}

	private final static Map<Type, String> MAP_TYPE_SIGNATURE;
	static {
		MAP_TYPE_SIGNATURE = new HashMap<>();
		MAP_TYPE_SIGNATURE.put(Type.RSA, "SHA256withRSA");
		MAP_TYPE_SIGNATURE.put(Type.EC, "SHA256withECDSA");
	}

	public JwtSignatureValidator(TokenKeyServiceWithCache tokenKeyService) {
		assertNotNull(tokenKeyService, "tokenKeyService must not be null.");

		this.tokenKeyService = tokenKeyService;
	}

	@Override
	public ValidationResult validate(Token token) {
		return validate(token.getAppToken(),
				token.getHeaderValueAsString(ALGORITHM_PARAMETER_NAME),
				token.getHeaderValueAsString(KEY_ID_PARAMETER_NAME));
	}

	public ValidationResult validate(String token, String tokenAlgorithm, @Nullable String tokenKeyId) {
		assertNotEmpty(token, "token must not be null / empty string.");
		assertNotEmpty(tokenAlgorithm, "tokenAlgorithm must not be null / empty string.");

		/*if(!isTokenKeyUrlValid(tokenKeyUrl, serviceConfiguration.getUaaDomain())) {
				return ValidationResults.createInvalid("JKU of token header is not trusted.");
		}*/

		Type keyType = getKeyTypeForAlgorithm(tokenAlgorithm);

		PublicKey publicKey = getPublicKey(keyType, tokenKeyId != null ? tokenKeyId : DEFAULT_KEY_ID);
		if (publicKey == null) {
			return ValidationResults.createInvalid("There is no JSON Web Token Key to prove the identity of the JWT.");
		}
		try {
			if(!isTokenSignatureValid(token, keyType, publicKey)) {
				return ValidationResults.createInvalid("Signature verification failed.");
			}
		} catch (Exception e) {
			LOGGER.error("Error during JSON Web Signature could not be verified.", e);
			return ValidationResults.createInvalid(e.getMessage());
		}
		return ValidationResults.createValid();
	}

	private Type getKeyTypeForAlgorithm(String tokenAlgorithm) {
		Type keyType = MAP_ALGORITHM_TYPE.get(tokenAlgorithm);
		if(keyType != null) {
			return keyType;
		}
		throw new IllegalArgumentException("JWT token with signature algorithm " + tokenAlgorithm + " can not be verified.");
	}

	private Signature getSignatureForAlgorithm(Type keyType) {
		String algorithm = MAP_TYPE_SIGNATURE.get(keyType);
		if(algorithm != null) {
			try {
				return Signature.getInstance(algorithm);
			} catch (NoSuchAlgorithmException e) {
				// should never happen
			}
		}
		throw new IllegalArgumentException("JWT token with signature algorithm " + keyType.value() + " can not be verified.");
	}

	@Nullable
	private PublicKey getPublicKey(Type keyType, String keyId) {
		return tokenKeyService.getPublicKey(keyType, keyId);
	}

	private boolean isTokenSignatureValid(String token, Type keyType, PublicKey publicKey) throws
			SignatureException, InvalidKeyException {
		Signature publicSignature = getSignatureForAlgorithm(keyType);

		String[] tokenHeaderPayloadSignature = token.split(Pattern.quote("."));
		if(tokenHeaderPayloadSignature.length != 3) {
			throw new IllegalArgumentException("JWT token does not consist of 'header'.'payload'.'signature'.");
		}
		String headerAndPayload = new StringBuilder( tokenHeaderPayloadSignature[0]).append( "." ).append(tokenHeaderPayloadSignature[1]).toString();

		publicSignature.initVerify(publicKey);
		publicSignature.update(headerAndPayload.getBytes( UTF_8)); // provide data

		byte[] decodedSignatureBytes = Base64.getUrlDecoder().decode(tokenHeaderPayloadSignature[2]);

		boolean isSignatureValid = publicSignature.verify(decodedSignatureBytes);
		if(!isSignatureValid) {
			LOGGER.warn("Error: Signature of JWT Token is not valid: the identity provided by the JSON Web Token Key can not be verified");
		}
		return isSignatureValid;
	}

	// TODO move to XsuaaIssuerValidator
	/*private boolean isTokenKeyUrlValid(String jku, String identityServiceDomain) {
		URI jkuUri;
		try {
			jkuUri = new URI(jku);
		} catch (URISyntaxException e) {
			LOGGER.warn("Error: JKU of token header '{}' is not a valid URI", jku);
			return false;
		}
		if(!jkuUri.getHost().endsWith(identityServiceDomain)) {
			LOGGER.warn("Error: Do not trust jku '{}' because it does not match uaa domain '{}'",
					jku, identityServiceDomain);
			return false;
		}
		return true;
	}*/
}
