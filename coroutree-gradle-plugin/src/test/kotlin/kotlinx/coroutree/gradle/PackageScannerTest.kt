package kotlinx.coroutree.gradle

import org.junit.jupiter.api.io.TempDir
import java.io.File
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

    @Test
    fun windowsLineEndings() {
        assertPackage("com.acme", "// header\r\n\r\npackage com.acme\r\n\r\nclass A\r\n")
        assertPackage("com.acme", "@file:JvmName(\"A\")\r\npackage com.acme\r\n")
        assertEquals("com.acme", PackageScanner.packageOf("package com.acme;\r\nclass A {}\r\n", kotlin = false))
    }

    @Test
    fun whatLooksLikeADeclarationButIsNot() {
        assertPackage("", "package")
        assertPackage("", "packagecom.acme")
        assertPackage("", "import a.b\npackage com.acme") // too late: the scan stops at the first other token
        assertPackage("", "val text = \"\"\"\npackage com.acme\n\"\"\"")
    }

    @Test
    fun annotationArgumentsAreSkippedWhateverTheyContain() {
        assertPackage("com.acme", "@file:Suppress(\"a\\\"b)\")\npackage com.acme")
        assertPackage("com.acme", "@file:Suppress(/* ) */ \"x\" // )\n)\npackage com.acme")
        assertPackage("com.acme", "@file:OptIn(A::class, B::class) @file:JvmName(\"X\") package com.acme")
        assertEquals("com.acme", PackageScanner.packageOf("@Generated(value = {\"a)\", \"b\"}, c = ')', d = '\\'')\npackage com.acme;", kotlin = false))
        assertEquals("com.acme", PackageScanner.packageOf("@Outer(@Inner(names = {\"(\"}))\n@Marker package com.acme;", kotlin = false))
    }

    @Test
    fun scriptHeaderAndBackticksRightAfterTheKeyword() {
        assertPackage("com.acme", "#!/usr/bin/env kotlin\npackage com.acme")
        assertPackage("in.acme", "package`in`.acme")
    }

    @Test
    fun namesAreNotLimitedToAscii() {
        assertPackage("com.ünïcode.日本", "package com.ünïcode.日本\n\nclass A")
    }

    // In Java a declaration ends at its semicolon, and the name may be broken wherever white space is allowed.
    @Test
    fun javaPackageNameMaySpanLines() {
        assertEquals("com.acme.util", PackageScanner.packageOf("package com.acme\n    .util;\n\nclass A {}", kotlin = false))
        assertEquals("com.acme.util", PackageScanner.packageOf("package com. // why\n    acme.\n    util;", kotlin = false))
    }

    // ------------------------------------------------------------------ files

    @TempDir
    lateinit var dir: File

    private fun source(name: String, bytes: ByteArray): File = File(dir, name).apply { writeBytes(bytes) }

    @Test
    fun theFileNameDecidesTheLanguage() {
        val text = "/* a header that mentions /* once */\npackage com.acme;\n"
        assertEquals("com.acme", PackageScanner.packageOf(source("A.java", text.toByteArray())))
        assertEquals("", PackageScanner.packageOf(source("A.kt", text.toByteArray())))
        assertEquals("com.acme.api", PackageScanner.packageOf(source("package-info.java", "/** Docs. */\n@Deprecated\npackage com.acme.api;\n".toByteArray())))
    }

    @Test
    fun filesAreReadAsUtf8WithOrWithoutAByteOrderMark() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals("com.acme", PackageScanner.packageOf(source("Bom.kt", bom + "package com.acme\n".toByteArray())))
        assertEquals("com.acme", PackageScanner.packageOf(source("Bom.java", bom + "// header\npackage com.acme;\n".toByteArray())))
        assertEquals("com.ünïcode", PackageScanner.packageOf(source("Unicode.kt", "// © Åcme\npackage com.ünïcode\n".toByteArray(Charsets.UTF_8))))
        assertEquals("", PackageScanner.packageOf(source("Empty.kt", ByteArray(0))))
    }

    @Test
    fun aFileInAnotherEncodingStillHasItsPackage() {
        // A legacy header: bytes that are not UTF-8 must neither fail the build nor hide the declaration behind them.
        val latin1 = "// Copyright © Société Générale, façade\npackage com.acme.legacy;\n".toByteArray(Charsets.ISO_8859_1)
        assertEquals("com.acme.legacy", PackageScanner.packageOf(source("Legacy.java", latin1)))
    }

    @Test
    fun onlyTheHeaderIsReadHoweverLargeTheFile() {
        val body = "fun f() = \"package not.this\"\n".repeat(100_000)
        assertEquals("com.acme", PackageScanner.packageOf(source("Large.kt", ("package com.acme\n\n$body").toByteArray())))
        // A header beyond any license: the file is in the root package as far as the index is concerned, and the build goes on.
        val header = "// " + "x".repeat(97) + "\n"
        assertEquals("", PackageScanner.packageOf(source("Header.kt", (header.repeat(700) + "package com.acme\n").toByteArray())))
    }
}
