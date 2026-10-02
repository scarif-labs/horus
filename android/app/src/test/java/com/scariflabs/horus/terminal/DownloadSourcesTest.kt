package com.scariflabs.horus.terminal

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSourcesTest {

  @Test
  fun `the default source downloads the pinned rootfs url`() {
    assertEquals(AlpineRootfsCatalog.ROOTFS_URL, DownloadSources().rootfsUrl)
    assertEquals(listOf("HORUS_ALPINE_MIRROR=${DownloadSources.DEFAULT_ALPINE_MIRROR}"), DownloadSources().guestEnvironment())
  }

  @Test
  fun `a mirror serves the same rootfs path and sets the npm registry`() {
    val sources = DownloadSources(
      alpineMirror = "https://mirrors.tuna.tsinghua.edu.cn/alpine",
      npmRegistry = "https://registry.npmmirror.com",
    )
    assertEquals(
      "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.0-aarch64.tar.gz",
      sources.rootfsUrl,
    )
    assertEquals(
      listOf("HORUS_ALPINE_MIRROR=https://mirrors.tuna.tsinghua.edu.cn/alpine", "npm_config_registry=https://registry.npmmirror.com"),
      sources.guestEnvironment(),
    )
  }

  @Test
  fun `only plain https urls are accepted`() {
    assertEquals("https://mirrors.ustc.edu.cn/alpine", DownloadSources.normalize(" https://mirrors.ustc.edu.cn/alpine/ "))
    assertEquals("https://mirror.example:8443/a/b", DownloadSources.normalize("https://mirror.example:8443/a/b"))
    for (bad in listOf(
      "http://mirrors.ustc.edu.cn/alpine",
      "https://user@mirror.example/alpine",
      "https://mirror.example/alpine?x=1",
      "https://mirror.example/alpine#x",
      "https://mirror.example/al pine",
      "https://mirror.example/\$(reboot)",
      "ftp://mirror.example",
      "",
      "https://" + "a".repeat(DownloadSources.MAX_URL_LENGTH),
    )) {
      assertNull(bad, DownloadSources.normalize(bad))
    }
  }

  @Test
  fun `settings round trip and ignore invalid stored values`() {
    val root = Files.createTempDirectory("horus-download-sources").toFile()
    try {
      val file = root.resolve("settings/download-sources.json")
      val settings = DownloadSourceSettings(file)
      assertEquals(DownloadSources(), settings.read())

      val sources = DownloadSources("https://mirrors.aliyun.com/alpine", "https://registry.npmmirror.com")
      assertTrue(settings.write(sources))
      assertEquals(sources, settings.read())

      file.writeText("{\"alpineMirror\":\"http://insecure.example/alpine\",\"npmRegistry\":\"https://registry.npmmirror.com\"}")
      assertEquals(DownloadSources(npmRegistry = "https://registry.npmmirror.com"), settings.read())

      assertTrue(settings.write(DownloadSources()))
      assertEquals(DownloadSources(), settings.read())
    } finally {
      root.deleteRecursively()
    }
  }
}
