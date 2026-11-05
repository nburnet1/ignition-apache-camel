const { EleventyHtmlBasePlugin } = require("@11ty/eleventy");
const syntaxHighlight = require("@11ty/eleventy-plugin-syntaxhighlight");

module.exports = function (eleventyConfig) {
  const pathPrefix = process.env.PATH_PREFIX || "/";
  const repositorySlug =
    process.env.GITHUB_REPOSITORY || "nburnet1/ignition-apache-camel";
  const sourceRepositoryUrl =
    process.env.SOURCE_REPOSITORY_URL || `https://github.com/${repositorySlug}`;

  eleventyConfig.addPlugin(EleventyHtmlBasePlugin, { baseHref: pathPrefix });
  eleventyConfig.addPlugin(syntaxHighlight);
  eleventyConfig.addPassthroughCopy({ "_docs/assets": "assets" });
  eleventyConfig.addGlobalData("layout", "base.njk");
  eleventyConfig.addGlobalData("sourceRepositoryUrl", sourceRepositoryUrl);

  return {
    pathPrefix,
    dir: {
      input: "_docs",
      includes: "_includes",
      data: "_data",
      output: "_site"
    },
    htmlTemplateEngine: "njk",
    markdownTemplateEngine: "njk"
  };
};
