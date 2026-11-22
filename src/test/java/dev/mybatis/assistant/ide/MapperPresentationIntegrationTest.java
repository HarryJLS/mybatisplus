package dev.mybatis.assistant.ide;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.navigation.NavigationGutterIconRenderer;
import com.intellij.ide.IconProvider;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.*;
import com.intellij.psi.xml.*;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.util.*;

/** Verifies presentation selection and the actual targets behind clickable markers. */
public final class MapperPresentationIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    private final MapperIconProvider icons = new MapperIconProvider();
    private XmlTag xml(String text) {
        XmlFile file = (XmlFile)myFixture.addFileToProject("sql/Queries.xml", text);
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        return file.getRootTag();
    }
    private List<LineMarkerInfo<?>> markers(PsiElement element) {
        List<LineMarkerInfo<?>> markers = new ArrayList<>();
        new MapperGutter().collectSlowLineMarkers(List.of(element), markers);
        return markers;
    }
    private PsiElement content(XmlAttribute attribute) {
        return attribute.getContainingFile().findElementAt(attribute.getValueElement().getValueTextRange().getStartOffset());
    }
    private void assertNavigation(PsiElement source, List<? extends PsiElement> targets) {
        var markers = markers(source);assertEquals(1, markers.size());
        var marker = markers.getFirst();assertSame(source.getContainingFile() instanceof XmlFile ? AssistantIcons.JAVA_MAPPER : AssistantIcons.XML_MAPPER, marker.getIcon());assertNotNull(marker.getNavigationHandler());
        var renderer = (NavigationGutterIconRenderer)marker.getNavigationHandler();
        assertTrue(renderer.isNavigateAction());assertEquals(targets, renderer.getTargetElements());
    }
    public void testRegisteredProviderAndSvgLoad() throws Exception {
        assertTrue(IconProvider.EXTENSION_POINT_NAME.getExtensionList().stream().anyMatch(p -> p instanceof MapperIconProvider));
        // Headless fixtures disable IconLoader rasterization; use the platform SVG renderer directly.
        for (String path : List.of("/icons/mapperXml.svg", "/icons/mapperXml_dark.svg", "/icons/mapperJava.svg", "/icons/mapperJava_dark.svg")) {
            var resource = AssistantIcons.class.getResource(path);assertNotNull(resource);
            var image = com.intellij.util.SVGLoader.load(resource, 1f);
            assertEquals(16, image.getWidth(null));assertEquals(16, image.getHeight(null));
        }
    }
    public void testXmlRootSelectsIconAndEditingRemovesIt() {
        XmlTag root = xml("<mapper namespace='sample.Missing'/>");assertSame(AssistantIcons.XML_MAPPER, icons.getIcon(root.getContainingFile(), 0));
        WriteCommandAction.runWriteCommandAction(getProject(), () -> { root.setName("configuration"); });
        assertNull(icons.getIcon(root.getContainingFile(), 0));assertNull(icons.getIcon(myFixture.addFileToProject("Mapper.xml", "<beans/>"), 0));
    }
    public void testAnnotatedInterfaceFileAndOrdinaryJava() {
        myFixture.addClass("package org.apache.ibatis.annotations; public @interface Mapper {}");
        PsiClass cls = myFixture.addClass("package sample; @org.apache.ibatis.annotations.Mapper public interface Queries {}");
        assertSame(AssistantIcons.JAVA_MAPPER, icons.getIcon(cls, 0));assertSame(AssistantIcons.JAVA_MAPPER, icons.getIcon(cls.getContainingFile(), 0));
        PsiClass ordinary = myFixture.addClass("package sample; public interface OrdinaryMapper {}");
        assertNull(icons.getIcon(ordinary, 0));assertNull(icons.getIcon(ordinary.getContainingFile(), 0));
        PsiClass entity = myFixture.addClass("package sample; @org.apache.ibatis.annotations.Mapper public class Entity {}");
        assertNull(icons.getIcon(entity, 0));assertNull(icons.getIcon(entity.getContainingFile(), 0));
    }
    public void testXmlAssociationChangesInterfaceIcon() {
        PsiClass cls = myFixture.addClass("package sample; public interface Queries { void find(); }");
        XmlTag root = xml("<mapper namespace='sample.Queries'><select id='find'>SELECT 1</select></mapper>");
        assertSame(AssistantIcons.JAVA_MAPPER, icons.getIcon(cls, 0));assertSame(AssistantIcons.JAVA_MAPPER, icons.getIcon(cls.getContainingFile(), 0));
        WriteCommandAction.runWriteCommandAction(getProject(), () -> { root.setAttribute("namespace", "sample.Other"); });
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        assertNull(icons.getIcon(cls, 0));assertNull(icons.getIcon(cls.getContainingFile(), 0));
    }
    public void testFishMarkersNavigateInBothDirectionsAndKeepMultipleXmlTargets() {
        PsiClass cls = myFixture.addClass("package sample; public interface Queries { void find(); }");
        XmlTag root = xml("<mapper namespace='sample.Queries'><select id='find'>SELECT 1</select></mapper>");
        XmlTag second = ((XmlFile)myFixture.addFileToProject("sql/Other.xml", "<mapper namespace='sample.Queries'><select id='find'>SELECT 2</select></mapper>")).getRootTag();
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());PsiMethod method = cls.getMethods()[0];
        assertNavigation(cls.getNameIdentifier(), MapperModel.mapperRoots(cls));assertEquals(2, MapperModel.mapperRoots(cls).size());
        assertNavigation(method.getNameIdentifier(), MapperModel.statements(method));assertEquals(2, MapperModel.statements(method).size());
        assertNavigation(content(root.getAttribute("namespace")), List.of(cls));
        assertNavigation(content(second.findFirstSubTag("select").getAttribute("id")), List.of(method));
    }
    public void testNoMarkersWithoutTargetsAndGutterSetting() {
        XmlTag root = xml("<mapper namespace='sample.Missing'><select id='find'>SELECT 1</select></mapper>");
        assertTrue(markers(content(root.getAttribute("namespace"))).isEmpty());
        assertTrue(markers(content(root.findFirstSubTag("select").getAttribute("id"))).isEmpty());
        PsiClass cls = myFixture.addClass("package sample; public interface Missing { void find(); }");IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        var settings = AssistantSettings.get(getProject());boolean previous = settings.gutter;
        try {
            settings.gutter = false;assertTrue(markers(cls.getNameIdentifier()).isEmpty());
            assertTrue(markers(content(root.getAttribute("namespace"))).isEmpty());
            assertSame(AssistantIcons.XML_MAPPER, icons.getIcon(root.getContainingFile(), 0));
        } finally { settings.gutter = previous; }
    }
    public void testLegacyDaoIconsAndOnDemandLookupMarker() {
        PsiClass cls = myFixture.addClass("package sample; public interface Queries { void find(); void lookup(); }");
        XmlTag root = xml("<sqlMap namespace='sample.Queries'><select id='find'>SELECT 1</select></sqlMap>");
        assertSame(AssistantIcons.XML_MAPPER, icons.getIcon(root.getContainingFile(), 0));assertSame(AssistantIcons.JAVA_MAPPER, icons.getIcon(cls.getContainingFile(), 0));
        assertNavigation(cls.getNameIdentifier(), List.of(root));assertNavigation(content(root.getAttribute("namespace")), List.of(cls));
        var lookup = markers(cls.findMethodsByName("lookup", false)[0].getNameIdentifier());assertEquals(1, lookup.size());
        assertSame(AssistantIcons.XML_MAPPER, lookup.getFirst().getIcon());assertNotNull(lookup.getFirst().getNavigationHandler());
        assertEquals("从 DAO 实现查找 iBATIS XML", lookup.getFirst().getLineMarkerTooltip());
    }
    public void testWholeXmlPassCreatesOneMarkerPerAttributeWithoutQuoteOptions() {
        PsiClass cls = myFixture.addClass("package sample; public interface Queries { void find(); void load(); }");
        XmlTag root = xml("<mapper namespace=\"sample.Queries\">\n<select id=\"find\">SELECT 1</select>\n<insert id='load'>INSERT INTO t VALUES (1)</insert>\n</mapper>");
        var leaves = com.intellij.psi.util.PsiTreeUtil.collectElements(root.getContainingFile(), e -> e.getFirstChild() == null);
        List<LineMarkerInfo<?>> markers = new ArrayList<>();
        new MapperGutter().collectSlowLineMarkers(Arrays.asList(leaves), markers);
        assertEquals("namespace and each statement should have exactly one marker", 3, markers.size());
        assertEquals(List.of("sample.Queries", "find", "load"), markers.stream().map(m -> m.getElement().getText()).toList());
        for (var marker : markers) assertEquals(1, ((NavigationGutterIconRenderer)marker.getNavigationHandler()).getTargetElements().size());
        for (XmlAttribute attr : List.of(root.getAttribute("namespace"), root.findFirstSubTag("select").getAttribute("id"), root.findFirstSubTag("insert").getAttribute("id"))) {
            var value = attr.getValueElement();assertTrue(markers(value.getFirstChild()).isEmpty());assertTrue(markers(value.getLastChild()).isEmpty());
        }
        assertNavigation(content(root.getAttribute("namespace")), List.of(cls));
    }
}
