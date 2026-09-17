package kotlinx.coroutree.gradle

import kotlin.test.Test
import kotlin.test.assertEquals

class PackageScannerTest {
    private fun assertPackage(expected: String, source: String) = assertEquals(expected, PackageScanner.packageOf(source))

    @Test
    fun plainDeclarations() {
        assertPackage("com.acme", "package com.acme\n\nfun main() {}")
        assertPackage("com.acme.util", "package com.acme.util;\n\npublic class A {}")
        assertPackage("single", "package single")
        assertPackage("com.acme", "﻿package com.acme")
    }

    @Test
    fun noDeclarationMeansRootPackage() {
        assertPackage("", "")
        assertPackage("", "fun main() {}")
        assertPackage("", "import java.util.List;\nclass A {}")
        assertPackage("", "// package com.commented.out\nclass A")
        assertPackage("", "packageName()")
        assertPackage("", "val packages = 1")
    }

    @Test
    fun commentsBeforeTheDeclaration() {
        assertPackage(
            "com.acme",
            """
            /*
             * Copyright. package not.this.one
             */
            // package nor.this
            /* nested /* package still.not */ comment */
            package com.acme
            """.trimIndent(),
        )
        assertPackage("", "/* never closed package a.b")
    }

    @Test
    fun fileAnnotationsBeforeTheDeclaration() {
        assertPackage("com.acme", "@file:JvmName(\"Util\")\npackage com.acme")
        assertPackage("com.acme", "@file:Suppress(\"a)\", \"package x.y\")\n@file:JvmMultifileClass\npackage com.acme")
        assertPackage("com.acme", "@file:[JvmName(\"X\") Suppress(\"y\")]\npackage com.acme")
        assertPackage("com.acme", "@file:Suppress(\"\"\"raw ) \" text\"\"\")\npackage com.acme")
        assertPackage("com.acme.api", "@javax.annotation.ParametersAreNonnullByDefault\n@Deprecated(since = \"1\")\npackage com.acme.api;")
    }

    @Test
    fun unusualButLegalSpelling() {
        assertPackage("com.acme", "package   com . acme ;")
        assertPackage("com.acme", "package\n    com.acme;")
        assertPackage("com.in.acme", "package com.`in`.acme")
        assertPackage("com.acme", "package com./* why */acme")
        assertPackage("com.acme", "package com.acme// trailing\nclass A")
        assertPackage("com", "package com\n.acme") // a line break ends a Kotlin package name
    }

    @Test
    fun blockCommentsNestInKotlinOnly() {
        val source = "/* a header that mentions /* once */\npackage com.acme;\n"
        assertEquals("com.acme", PackageScanner.packageOf(source, kotlin = false))
        assertEquals("", PackageScanner.packageOf(source, kotlin = true), "to Kotlin the comment is still open")
    }

    @Test
    fun blankAfterTheUseSiteTargetOfAFileAnnotation() {
        assertPackage("com.acme", "@file: JvmName(\"Main\")\npackage com.acme\n")
    }
}
