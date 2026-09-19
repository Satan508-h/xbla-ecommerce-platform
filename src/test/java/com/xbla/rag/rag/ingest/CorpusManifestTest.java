package com.xbla.rag.rag.ingest;

import com.xbla.rag.config.KbProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 语料清单加载器的单元测试。
 *
 * <p>纯文件解析测试，不起 Spring、不连数据库。
 *
 * <p>这个类守的是<b>一条安静的错误路径</b>：{@code doc_type} 标错的时候，
 * 入库会全部成功、状态全部正常、检索也照跑 —— 只有阶段 5 的意图定向检索
 * 会表现为「某一类查询永远返回空」，和「知识库里确实没有」无法区分。
 *
 * <p>所以「清单能读懂」不是重点，<b>「清单读不懂时会不会安静地继续」</b>才是。
 * 本类的测试重心在后者。
 */
@DisplayName("CorpusManifest · 语料清单")
class CorpusManifestTest {

    @TempDir
    Path corpusDir;

    /** 把给定内容写成语料目录下的 manifest.yml，并造一个指向该目录的加载器 */
    private CorpusManifest manifestOf(String yaml) throws IOException {
        Files.writeString(corpusDir.resolve(CorpusManifest.FILE_NAME), yaml, StandardCharsets.UTF_8);
        KbProperties properties = new KbProperties();
        properties.setCorpusDir(corpusDir.toString());
        return new CorpusManifest(properties);
    }

    /** 只造加载器，不写清单文件 —— 用于测「文件不存在」 */
    private CorpusManifest manifestWithoutFile() {
        KbProperties properties = new KbProperties();
        properties.setCorpusDir(corpusDir.toString());
        return new CorpusManifest(properties);
    }

    // ============================================================
    // 一、正常路径
    // ============================================================

    @Nested
    @DisplayName("一、正常路径")
    class HappyPath {

        @Test
        @DisplayName("逐文件解析出 doc_type")
        void parsesDocTypePerFile() throws IOException {
            CorpusManifest manifest = manifestOf("""
                    files:
                      - name: 促销活动规则.md
                        doc_type: 3
                      - name: 售后FAQ.xlsx
                        doc_type: 4
                      - name: 商品说明书-星辰X1.pdf
                        doc_type: 5
                    """);

            CorpusManifest.Manifest loaded = manifest.load();

            assertThat(loaded.present()).isTrue();
            assertThat(loaded.entries()).hasSize(3);
            assertThat(loaded.find("促销活动规则.md")).get()
                    .extracting(CorpusManifest.Entry::docType).isEqualTo(3);
            assertThat(loaded.find("售后FAQ.xlsx")).get()
                    .extracting(CorpusManifest.Entry::docType).isEqualTo(4);
            assertThat(loaded.find("商品说明书-星辰X1.pdf")).get()
                    .extracting(CorpusManifest.Entry::docType).isEqualTo(5);
        }

        @Test
        @DisplayName("保持清单里的书写顺序（WARN 日志的顺序才稳定）")
        void keepsDeclarationOrder() throws IOException {
            CorpusManifest manifest = manifestOf("""
                    files:
                      - name: c.md
                        doc_type: 1
                      - name: a.md
                        doc_type: 2
                      - name: b.md
                        doc_type: 3
                    """);

            assertThat(manifest.load().entries().keySet())
                    .containsExactly("c.md", "a.md", "b.md");
        }

        @Test
        @DisplayName("note 是给人和块标量都能读，且不影响解析")
        void toleratesNoteAndBlockScalar() throws IOException {
            CorpusManifest manifest = manifestOf("""
                    files:
                      - name: 商品导购指南.md
                        doc_type: 1
                        note: |
                          这是块标量，跨多行。
                          程序不读它，但解析不能崩
                    """);

            CorpusManifest.Manifest loaded = manifest.load();

            assertThat(loaded.find("商品导购指南.md")).get()
                    .extracting(CorpusManifest.Entry::docType).isEqualTo(1);
        }

        @Test
        @DisplayName("没声明的文件返回 empty，不抛异常（由调用方决定回落）")
        void unknownFileIsEmpty() throws IOException {
            CorpusManifest manifest = manifestOf("""
                    files:
                      - name: 促销活动规则.md
                        doc_type: 3
                    """);

            assertThat(manifest.load().find("没登记过的文件.md")).isEmpty();
        }
    }

    // ============================================================
    // 二、★ 清单文件不存在 —— 刻意【不】抛异常
    // ============================================================

    @Nested
    @DisplayName("二、文件不存在时回落而不是失败")
    class MissingFile {

        @Test
        @DisplayName("返回 present=false 的空清单，不抛异常")
        void missingFileIsNotAnError() {
            CorpusManifest manifest = manifestWithoutFile();

            assertThatCode(manifest::load).doesNotThrowAnyException();

            CorpusManifest.Manifest loaded = manifest.load();
            assertThat(loaded.present()).isFalse();
            assertThat(loaded.entries()).isEmpty();
            assertThat(loaded.find("任何文件.md")).isEmpty();
        }

        @Test
        @DisplayName("★ 对照组：同一台加载器，清单【存在】时 present=true")
        void contrastCheckPresent() throws IOException {
            // 和上一个测试成对：证明 present 这个标志真的在区分两种状态，
            // 而不是恒为 false（那样上面的断言就是恒真的废话）
            CorpusManifest manifest = manifestOf("""
                    files:
                      - name: a.md
                        doc_type: 1
                    """);

            assertThat(manifest.load().present()).isTrue();
        }
    }

    // ============================================================
    // 三、★ 格式错误必须吵 —— 每条都要能指出是哪个文件写错了
    // ============================================================

    @Nested
    @DisplayName("三、格式错误必须抛异常")
    class Malformed {

        /** 断言抛出的异常消息里带着出错的文件名，否则排查时找不到人 */
        private void expectFailure(String yaml, String... messageFragments) throws IOException {
            CorpusManifest manifest = manifestOf(yaml);
            assertThatThrownBy(manifest::load)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContainingAll(messageFragments);
        }

        @Test
        @DisplayName("doc_type 越界（超过词表上限）")
        void docTypeTooLarge() throws IOException {
            expectFailure("""
                    files:
                      - name: 某个文件.md
                        doc_type: 6
                    """, "某个文件.md", "越界");
        }

        @Test
        @DisplayName("doc_type 越界（0 不是合法值）")
        void docTypeTooSmall() throws IOException {
            expectFailure("""
                    files:
                      - name: 某个文件.md
                        doc_type: 0
                    """, "某个文件.md", "越界");
        }

        @Test
        @DisplayName("doc_type 不是数字")
        void docTypeNotANumber() throws IOException {
            expectFailure("""
                    files:
                      - name: 某个文件.md
                        doc_type: 售后政策
                    """, "某个文件.md", "doc_type");
        }

        @Test
        @DisplayName("缺 name 字段")
        void missingName() throws IOException {
            expectFailure("""
                    files:
                      - doc_type: 2
                    """, "name");
        }

        @Test
        @DisplayName("★ 同一个文件被声明两次")
        void duplicateDeclaration() throws IOException {
            // 两条记录谁生效取决于读的顺序 —— 那正是「安静地不确定」
            expectFailure("""
                    files:
                      - name: 重复.md
                        doc_type: 1
                      - name: 重复.md
                        doc_type: 5
                    """, "重复.md", "两次");
        }

        @Test
        @DisplayName("顶层缺 files")
        void missingFilesKey() throws IOException {
            expectFailure("""
                    别的键: 值
                    """, "files");
        }

        @Test
        @DisplayName("★ 多处错误一次性报全，不是遇到第一个就停")
        void reportsAllProblemsAtOnce() throws IOException {
            // 改清单时通常一次改好几处，逐条报错要来回跑很多遍
            expectFailure("""
                    files:
                      - name: 甲.md
                        doc_type: 9
                      - name: 乙.md
                        doc_type: 0
                      - doc_type: 2
                    """, "甲.md", "乙.md", "name");
        }

        @Test
        @DisplayName("★ 对照组：同一份清单把 doc_type 改合法后就不再抛")
        void contrastCheckValidPasses() throws IOException {
            // 和上面几条成对：证明抛异常真的是 doc_type 引起的，
            // 而不是「只要调 load() 就抛」——
            // 否则那些断言全是恒真的，测了等于没测
            CorpusManifest manifest = manifestOf("""
                    files:
                      - name: 甲.md
                        doc_type: 1
                      - name: 乙.md
                        doc_type: 5
                    """);

            assertThatCode(manifest::load).doesNotThrowAnyException();
        }
    }

    // ============================================================
    // 四、真实清单文件
    // ============================================================

    @Nested
    @DisplayName("四、仓库里真实的那份清单")
    class RealManifest {

        @Test
        @DisplayName("data/corpus/manifest.yml 能被解析，且声明数与目录里的语料文件数一致")
        void realManifestIsValid() throws IOException {
            Path real = Path.of("data/corpus", CorpusManifest.FILE_NAME);
            // 语料目录是 git 跟踪的，正常克隆后一定在。
            // 这里不 assumeTrue —— 缺了就是真问题，应该失败
            assertThat(real).exists();

            KbProperties properties = new KbProperties();
            properties.setCorpusDir("data/corpus");
            CorpusManifest.Manifest loaded = new CorpusManifest(properties).load();

            assertThat(loaded.present()).isTrue();

            // ★ 目录里【每个】语料文件都必须被声明。
            //   漏一个的后果不是报错，而是它静默走兜底 doc_type ——
            //   正是这个清单要消灭的那种错误，所以在这里钉死
            List<String> undeclared = new ArrayList<>();
            try (var stream = Files.list(Path.of("data/corpus"))) {
                stream.filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .filter(name -> !name.equals(CorpusManifest.FILE_NAME))
                        .filter(name -> loaded.find(name).isEmpty())
                        .forEach(undeclared::add);
            }
            assertThat(undeclared)
                    .as("语料目录里存在未被 manifest.yml 声明的文件")
                    .isEmpty();

            // 反向：清单里声明的文件也必须真的存在（清单过期了同样要报出来）
            List<String> stale = loaded.entries().keySet().stream()
                    .filter(name -> !Files.isRegularFile(Path.of("data/corpus", name)))
                    .toList();
            assertThat(stale)
                    .as("manifest.yml 声明了但目录里不存在的文件")
                    .isEmpty();
        }
    }
}
