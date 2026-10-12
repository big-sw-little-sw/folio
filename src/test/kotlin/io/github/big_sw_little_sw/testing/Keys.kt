package io.github.big_sw_little_sw.testing

import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

/** True if a signature of [privateKey] verifies with [publicKey]. Then the two keys are one Ed25519 key pair. */
fun signsFor(privateKey: PrivateKey, publicKey: PublicKey): Boolean {
    val message = "challenge".toByteArray()
    val signer = Signature.getInstance("Ed25519")
    signer.initSign(privateKey)
    signer.update(message)
    val verifier = Signature.getInstance("Ed25519")
    verifier.initVerify(publicKey)
    verifier.update(message)
    return verifier.verify(signer.sign())
}
