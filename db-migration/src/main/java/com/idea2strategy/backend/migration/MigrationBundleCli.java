package com.idea2strategy.backend.migration;

import java.nio.file.Path;
import java.util.Arrays;

public final class MigrationBundleCli {
    private MigrationBundleCli() {}

    public static void main(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: <central-migration-directory> <output-directory> [contribution-root ...]");
        }
        var contributions = Arrays.stream(args)
                .skip(2)
                .map(Path::of)
                .map(MigrationContribution::load)
                .toList();
        var bundle = CanonicalMigrationBundleAssembler.assemble(
                Path.of(args[0]), contributions, Path.of(args[1]));
        System.out.println(bundle.sha256());
    }
}
