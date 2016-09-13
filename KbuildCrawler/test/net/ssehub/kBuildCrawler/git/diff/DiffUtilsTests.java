import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

import net.ssehub.kBuildCrawler.AllTests;
     * Helper method to read a diff from a text file.
     * @param fileName A text file within the {@link AllTests#TESTDATA} folder containing a diff file.
     * @return The content of the specified file.
     */
    private static String loadDiff(String fileName) {
        File file = new File(AllTests.TESTDATA, fileName);
        String content = null;
        try {
            content = org.apache.commons.io.FileUtils.readFileToString(file, (Charset) null);
            Assert.assertNotNull(content);
        } catch (IOException e) {
            Assert.fail("Could not read file \"" + file.getAbsolutePath() + "\": " + e.getMessage());
        }
        
        return content;
    }
    
    /**
     * Tests {@link DiffUtils#parseDiff(String)} if only one file was added, with one hunk.
        String[] lines = diffString.split(IOUtils.LINEFEED_REGEX);
        Assert.assertEquals(1, diff.getHunks().size());
        
        InFileDiff hunk = diff.getHunks().get(0);
        Assert.assertEquals(0, hunk.getStartLineBefore());
        Assert.assertEquals(0, hunk.getNumberOfChangesLinesBefore());
        Assert.assertEquals(1, hunk.getStartLineAfter());
        Assert.assertEquals(8, hunk.getNumberOfChangesLinesAfter());
    }
    
    /**
     * Tests {@link DiffUtils#parseDiff(String)} if only one file was added, with two hunks.
     */
    @Test
    public void testParseFileDiff_TwoHunksChanged() {
        String diffString = "diff --git a/KbuildCrawler/test/net/ssehub/kBuildCrawler/git/AllGitTests.java\n"
            + "b/KbuildCrawler/test/net/ssehub/kBuildCrawler/git/AllGitTests.java\n"
            + "index 42aecb2..78126a7 100644\n"
            + "--- a/KbuildCrawler/test/net/ssehub/kBuildCrawler/git/AllGitTests.java\n"
            + "+++ b/KbuildCrawler/test/net/ssehub/kBuildCrawler/git/AllGitTests.java\n"
            + "@@ -4,8 +4,12 @@ import org.junit.runner.RunWith;\n"
            + "import org.junit.runners.Suite;\nimport org.junit.runners.Suite.SuiteClasses;\n\n"
            + "+import net.ssehub.kBuildCrawler.git.diff.AllGitDiffTests;\n"
            + "+\n"
            + "+@RunWith(Suite.class)\n"
            + "-@SuiteClasses({ GitUtilsTest.class })\n"
            + "+@SuiteClasses({\n"
            + "+    AllGitDiffTests.class,\n"
            + "+    GitUtilsTest.class })\n"
            + "public class AllGitTests {\n\n}\n"
            + "@@ -12,1 +16,1 @@\n"
            + "+// A Comment\n";
        String[] lines = diffString.split(IOUtils.LINEFEED_REGEX);
        
        FileDiff diff = DiffUtils.parseFileDiff(lines, 0, lines.length);
        Assert.assertNotNull(diff);
        Assert.assertEquals("KbuildCrawler/test/net/ssehub/kBuildCrawler/git/AllGitTests.java", diff.getFile());
        Assert.assertEquals(ChangeType.CHANGED, diff.getFileChange());
        Assert.assertEquals(2, diff.getHunks().size());
        
        InFileDiff hunk = diff.getHunks().get(0);
        Assert.assertEquals(4, hunk.getStartLineBefore());
        Assert.assertEquals(8, hunk.getNumberOfChangesLinesBefore());
        Assert.assertEquals(4, hunk.getStartLineAfter());
        Assert.assertEquals(12, hunk.getNumberOfChangesLinesAfter());
        
        hunk = diff.getHunks().get(1);
        Assert.assertEquals(12, hunk.getStartLineBefore());
        Assert.assertEquals(1, hunk.getNumberOfChangesLinesBefore());
        Assert.assertEquals(16, hunk.getStartLineAfter());
        Assert.assertEquals(1, hunk.getNumberOfChangesLinesAfter());
    }
    
    /**
     * Tests the {@link DiffUtils#parseDiff(String)} method.
     */
    @Test
    public void testParseDiff_MultipleFiles() {
        String diffString = loadDiff("diffMultipleFiles.diff");
        CommitDiff diff = DiffUtils.parseDiff(diffString);
        
        // Whole diff
        Assert.assertNotNull(diff);
        Assert.assertEquals(4, diff.getChangedFiles().size());
        
        // Files
        assertFileDiff(diff.getChangedFiles().get(0),
            "KbuildCrawler/test/net/ssehub/kBuildCrawler/mail/AllMailTests.java", ChangeType.CHANGED, 1);
        assertFileDiff(diff.getChangedFiles().get(1),
            "KbuildCrawler/test/net/ssehub/kBuildCrawler/mail/GZFolderMailSourceTest.java", ChangeType.ADDED, 1);
        assertFileDiff(diff.getChangedFiles().get(2),
            "KbuildCrawler/test/net/ssehub/kBuildCrawler/mail/ZipMailSourceTests.java", ChangeType.CHANGED, 2);
        assertFileDiff(diff.getChangedFiles().get(3),
            "KbuildCrawler/testdata/2016-August-copy.txt.gz", ChangeType.ADDED, 0);
        
    }

    /**
     * Helper method to test a {@link CommitDiff}.
     * @param fDiff The {@link CommitDiff} to test.
     * @param expectedFile The expected relative path of the associated file.
     * @param expectedChange The expected kind of change for the given file.
     * @param expectedHunks The expected number of changed code blocks.
     */
    private void assertFileDiff(FileDiff fDiff, String expectedFile, ChangeType expectedChange, int expectedHunks) {
        Assert.assertNotNull(fDiff);
        Assert.assertEquals(expectedFile, fDiff.getFile());
        Assert.assertEquals(expectedChange, fDiff.getFileChange());
        Assert.assertEquals(expectedHunks, fDiff.getHunks().size());