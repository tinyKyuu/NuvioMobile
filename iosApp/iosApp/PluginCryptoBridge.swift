import CryptoKit
import Foundation

private let nuvioCryptoSuccess: Int32 = 0
private let nuvioCryptoInvalidArgument: Int32 = -1
private let nuvioCryptoBufferTooSmall: Int32 = -2
private let nuvioCryptoOperationFailed: Int32 = -3
private let aesGcmTagSize = 16
private let aesGcmMinimumNonceSize = 12

@_cdecl("nuvio_aes_gcm_encrypt")
func nuvioAesGcmEncrypt(
    keyPointer: UnsafeRawPointer?,
    keyLength: Int,
    noncePointer: UnsafeRawPointer?,
    nonceLength: Int,
    plaintextPointer: UnsafeRawPointer?,
    plaintextLength: Int,
    outputPointer: UnsafeMutableRawPointer?,
    outputCapacity: Int,
    outputLength: UnsafeMutablePointer<Int>?
) -> Int32 {
    guard let outputLength else { return nuvioCryptoInvalidArgument }
    outputLength.pointee = 0

    guard isValidAesKeyLength(keyLength),
          nonceLength >= aesGcmMinimumNonceSize,
          plaintextLength >= 0,
          outputCapacity >= 0,
          let keyData = data(from: keyPointer, count: keyLength),
          let nonceData = data(from: noncePointer, count: nonceLength),
          let plaintext = data(from: plaintextPointer, count: plaintextLength)
    else {
        return nuvioCryptoInvalidArgument
    }

    let (requiredCapacity, overflow) = plaintextLength.addingReportingOverflow(aesGcmTagSize)
    guard !overflow else { return nuvioCryptoInvalidArgument }
    guard outputCapacity >= requiredCapacity else { return nuvioCryptoBufferTooSmall }

    do {
        let key = SymmetricKey(data: keyData)
        let nonce = try AES.GCM.Nonce(data: nonceData)
        let sealedBox = try AES.GCM.seal(plaintext, using: key, nonce: nonce)
        var ciphertextAndTag = sealedBox.ciphertext
        ciphertextAndTag.append(sealedBox.tag)
        return copy(ciphertextAndTag, to: outputPointer, length: outputLength)
    } catch {
        return nuvioCryptoOperationFailed
    }
}

@_cdecl("nuvio_aes_gcm_decrypt")
func nuvioAesGcmDecrypt(
    keyPointer: UnsafeRawPointer?,
    keyLength: Int,
    noncePointer: UnsafeRawPointer?,
    nonceLength: Int,
    ciphertextAndTagPointer: UnsafeRawPointer?,
    ciphertextAndTagLength: Int,
    outputPointer: UnsafeMutableRawPointer?,
    outputCapacity: Int,
    outputLength: UnsafeMutablePointer<Int>?
) -> Int32 {
    guard let outputLength else { return nuvioCryptoInvalidArgument }
    outputLength.pointee = 0

    guard isValidAesKeyLength(keyLength),
          nonceLength >= aesGcmMinimumNonceSize,
          ciphertextAndTagLength >= aesGcmTagSize,
          outputCapacity >= 0,
          let keyData = data(from: keyPointer, count: keyLength),
          let nonceData = data(from: noncePointer, count: nonceLength),
          let ciphertextAndTag = data(from: ciphertextAndTagPointer, count: ciphertextAndTagLength)
    else {
        return nuvioCryptoInvalidArgument
    }

    let ciphertextLength = ciphertextAndTagLength - aesGcmTagSize
    guard outputCapacity >= ciphertextLength else { return nuvioCryptoBufferTooSmall }

    do {
        let key = SymmetricKey(data: keyData)
        let nonce = try AES.GCM.Nonce(data: nonceData)
        let ciphertext = ciphertextAndTag.prefix(ciphertextLength)
        let tag = ciphertextAndTag.suffix(aesGcmTagSize)
        let sealedBox = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
        let plaintext = try AES.GCM.open(sealedBox, using: key)
        return copy(plaintext, to: outputPointer, length: outputLength)
    } catch {
        return nuvioCryptoOperationFailed
    }
}

private func isValidAesKeyLength(_ length: Int) -> Bool {
    length == 16 || length == 24 || length == 32
}

private func data(from pointer: UnsafeRawPointer?, count: Int) -> Data? {
    guard count >= 0 else { return nil }
    guard count > 0 else { return Data() }
    guard let pointer else { return nil }
    return Data(bytes: pointer, count: count)
}

private func copy(
    _ data: Data,
    to outputPointer: UnsafeMutableRawPointer?,
    length outputLength: UnsafeMutablePointer<Int>
) -> Int32 {
    guard !data.isEmpty else {
        outputLength.pointee = 0
        return nuvioCryptoSuccess
    }
    guard let outputPointer else { return nuvioCryptoInvalidArgument }

    data.copyBytes(to: outputPointer.assumingMemoryBound(to: UInt8.self), count: data.count)
    outputLength.pointee = data.count
    return nuvioCryptoSuccess
}
