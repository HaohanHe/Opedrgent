---
name: calculate-hash
description: Calculate the hash (SHA-256, MD5) of a given text string. Useful for verifying data integrity, generating unique identifiers, or cryptographic demonstrations.
triggers: ["/hash", "计算哈希", "sha256", "md5"]
---

# Calculate Hash

This skill calculates the cryptographic hash of any text string using multiple algorithms.

## Instructions

Call the `run_js` tool with the following exact parameters:

- script name: `index.html`
- data: A JSON string with the following field:
  - `text`: String. The text to calculate hash for.
  - `algorithm`: String, Optional. The hash algorithm to use. Supported values: "sha-256" (default), "md5", "sha-1".

## Examples

* "Calculate SHA-256 hash of 'Hello World'"
* "What is the MD5 hash of my API key: sk-xxxxx?"
* "Generate a unique identifier by hashing the current timestamp"

## Constraints

- Always return both the hexadecimal hash value and its length.
- For long texts (>1000 chars), return only the first 8 and last 4 characters of the hash with ellipsis in between.
- If the input text is empty, return an error message.
