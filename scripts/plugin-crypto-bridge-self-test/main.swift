import Foundation

private let key = [UInt8](repeating: 0, count: 16)
private let nonce = [UInt8](repeating: 0, count: 12)
private let plaintext = [UInt8](repeating: 0, count: 16)
private let expectedCiphertextAndTag = bytes(
    from: "0388dace60b6a392f328c2b971b2fe78ab6e47d42cec13bdf53a67b21257bddf"
)

let encrypted = encrypt(key: key, nonce: nonce, plaintext: plaintext)
precondition(encrypted.status == 0, "AES-GCM encryption failed with status \(encrypted.status)")
precondition(encrypted.data == expectedCiphertextAndTag, "AES-GCM known-answer encryption mismatch")

let decrypted = decrypt(key: key, nonce: nonce, ciphertextAndTag: encrypted.data)
precondition(decrypted.status == 0, "AES-GCM decryption failed with status \(decrypted.status)")
precondition(decrypted.data == plaintext, "AES-GCM round trip mismatch")

var tampered = encrypted.data
tampered[tampered.count - 1] ^= 0x01
precondition(
    decrypt(key: key, nonce: nonce, ciphertextAndTag: tampered).status == -3,
    "AES-GCM accepted a modified authentication tag"
)

precondition(
    encrypt(key: key, nonce: [UInt8](repeating: 0, count: 11), plaintext: plaintext).status == -1,
    "AES-GCM accepted a nonce shorter than CryptoKit permits"
)

print("PluginCryptoBridge AES-GCM checks passed")

private func encrypt(
    key: [UInt8],
    nonce: [UInt8],
    plaintext: [UInt8]
) -> (status: Int32, data: [UInt8]) {
    var output = [UInt8](repeating: 0, count: plaintext.count + 16)
    let outputCapacity = output.count
    var outputLength = 0
    let status = key.withUnsafeBytes { keyBytes in
        nonce.withUnsafeBytes { nonceBytes in
            plaintext.withUnsafeBytes { plaintextBytes in
                output.withUnsafeMutableBytes { outputBytes in
                    nuvioAesGcmEncrypt(
                        keyPointer: keyBytes.baseAddress,
                        keyLength: key.count,
                        noncePointer: nonceBytes.baseAddress,
                        nonceLength: nonce.count,
                        plaintextPointer: plaintextBytes.baseAddress,
                        plaintextLength: plaintext.count,
                        outputPointer: outputBytes.baseAddress,
                        outputCapacity: outputCapacity,
                        outputLength: &outputLength
                    )
                }
            }
        }
    }
    return (status, Array(output.prefix(outputLength)))
}

private func decrypt(
    key: [UInt8],
    nonce: [UInt8],
    ciphertextAndTag: [UInt8]
) -> (status: Int32, data: [UInt8]) {
    var output = [UInt8](repeating: 0, count: ciphertextAndTag.count - 16)
    let outputCapacity = output.count
    var outputLength = 0
    let status = key.withUnsafeBytes { keyBytes in
        nonce.withUnsafeBytes { nonceBytes in
            ciphertextAndTag.withUnsafeBytes { ciphertextBytes in
                output.withUnsafeMutableBytes { outputBytes in
                    nuvioAesGcmDecrypt(
                        keyPointer: keyBytes.baseAddress,
                        keyLength: key.count,
                        noncePointer: nonceBytes.baseAddress,
                        nonceLength: nonce.count,
                        ciphertextAndTagPointer: ciphertextBytes.baseAddress,
                        ciphertextAndTagLength: ciphertextAndTag.count,
                        outputPointer: outputBytes.baseAddress,
                        outputCapacity: outputCapacity,
                        outputLength: &outputLength
                    )
                }
            }
        }
    }
    return (status, Array(output.prefix(outputLength)))
}

private func bytes(from hex: String) -> [UInt8] {
    precondition(hex.count.isMultiple(of: 2))
    return stride(from: 0, to: hex.count, by: 2).map { offset in
        let start = hex.index(hex.startIndex, offsetBy: offset)
        let end = hex.index(start, offsetBy: 2)
        return UInt8(hex[start..<end], radix: 16)!
    }
}
