package org.zipp.ai.application.turn;

/** Package helper for source-aware SHA-256 binding validation. */
final class SourceCommitBindingDigest {

    private SourceCommitBindingDigest() {
    }

    static void required(String value, String name) {
        ContractValues.requiredText(value, name);
        if (value.length() != 64 || !value.chars().allMatch(character ->
                (character >= '0' && character <= '9')
                        || (character >= 'a' && character <= 'f'))) {
            throw new IllegalArgumentException(name + " must be a SHA-256 digest");
        }
    }
}
