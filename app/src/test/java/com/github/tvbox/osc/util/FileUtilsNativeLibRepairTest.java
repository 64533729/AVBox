package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class FileUtilsNativeLibRepairTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final byte[] ELF = {0x7F, 'E', 'L', 'F', 0x02, 0x01, 0x01, 0x00};

    private static final String XML_ERROR =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Error>\n  <Code>NoSuchKey</Code>\n</Error>";

    private File write(File dir, String name, byte[] content) throws Exception {
        File file = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content);
        }
        return file;
    }

    private File write(File dir, String name, String content) throws Exception {
        return write(dir, name, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void removesXmlErrorSavedAsSo() throws Exception {
        File root = folder.newFolder("files");
        File tv = new File(root, "TV");
        assertTrue(tv.mkdirs());
        File bogus = write(tv, "libwexproxy.so", XML_ERROR);
        File bogusTemp = write(tv, ".libwexproxyMZVs13PWOs", XML_ERROR);

        assertEquals(2, FileUtils.repairBogusNativeLibs(root));
        assertFalse(bogus.exists());
        assertFalse(bogusTemp.exists());
    }

    @Test
    public void removesTruncatedAndEmptySo() throws Exception {
        File root = folder.newFolder("files2");
        File shortFile = write(root, "a.so", new byte[]{0x7F, 'E'});
        File empty = write(root, "b.so", new byte[0]);

        assertEquals(2, FileUtils.repairBogusNativeLibs(root));
        assertFalse(shortFile.exists());
        assertFalse(empty.exists());
    }

    @Test
    public void removesHtmlSavedAsLib() throws Exception {
        File root = folder.newFolder("files3");
        File html = write(root, ".libproxy", "<!DOCTYPE html><html>404</html>");

        assertEquals(1, FileUtils.repairBogusNativeLibs(root));
        assertFalse(html.exists());
    }

    @Test
    public void keepsValidElfLibraries() throws Exception {
        File root = folder.newFolder("files4");
        File tv = new File(root, "TV");
        assertTrue(tv.mkdirs());
        File so = write(root, "libLoadNiMa.so", ELF);
        File temp = write(tv, ".libLoadNiMaz3tbtfmq2v", ELF);

        assertEquals(0, FileUtils.repairBogusNativeLibs(root));
        assertTrue(so.exists());
        assertTrue(temp.exists());
    }

    @Test
    public void keepsNonLibraryFiles() throws Exception {
        File root = folder.newFolder("files5");
        File cfg = write(root, ".wexcofig.json", "{}");
        File string = write(root, ".wexstring", XML_ERROR);
        File flag = write(root, "go_proxy_video", "{}");
        File db = write(root, "spider.db", XML_ERROR);
        File other = write(root, "libdata.txt", XML_ERROR);

        assertEquals(0, FileUtils.repairBogusNativeLibs(root));
        assertTrue(cfg.exists());
        assertTrue(string.exists());
        assertTrue(flag.exists());
        assertTrue(db.exists());
        assertTrue(other.exists());
    }

    @Test
    public void handlesMissingDirAndNestedLevels() throws Exception {
        assertEquals(0, FileUtils.repairBogusNativeLibs(new File(folder.getRoot(), "not-there")));

        File root = folder.newFolder("files6");
        File deep = new File(root, "a/b");
        assertTrue(deep.mkdirs());
        File bogus = write(deep, "x.so", XML_ERROR);
        assertEquals(1, FileUtils.repairBogusNativeLibs(root));
        assertFalse(bogus.exists());
    }

    @Test
    public void stopsAtDepthLimit() throws Exception {
        File root = folder.newFolder("files7");
        File tooDeep = new File(root, "a/b/c/d/e");
        assertTrue(tooDeep.mkdirs());
        File bogus = write(tooDeep, "x.so", XML_ERROR);

        assertEquals(0, FileUtils.repairBogusNativeLibs(root));
        assertTrue("超出深度上限的文件不应被删除", bogus.exists());
    }
}
