---
description: A recipe is a document that contains all the information necessary for Quine to execute any streaming task
---
# Recipes

## What is a Recipe

A Recipe is a document that contains all the information necessary for Quine to execute any batch or streaming data processing task. Recipes are built from components including:

* @ref:[Ingest Streams](../components/ingest-sources/ingest-sources.md) to read streaming data from sources and update graph data
* @ref:[Standing Queries](../components/standing-query-outputs.md) to transform graph data, and to produce aggregates and other outputs
* @ref:[Cypher expressions](../reference/cypher/cypher-language.md) to implement graph operations such as querying and updating data
* UI configuration to specialize the web user interface for the use-case that is the subject of the Recipe

## Running a Recipe

To run a Recipe, use the Quine command line argument `-r`, followed by the name of the Recipe (from @link[https://quine.io/recipes](https://quine.io/recipes)), or a local file (ending in `.json` or `.yaml`), or a URL. 

For example use the following command to run [the Ethereum Recipe](https://quine.io/recipes/ethereum-tag-propagation):

```shell
❯ java -jar quine.jar -r ethereum
```

Or to run a Recipe contained in a local file:

```shell
❯ java -jar quine.jar -r your-file.yaml
```

While Quine is running, a web user interface is available at [localhost:8080](http://localhost:8080/).

## Creating a Recipe

A Recipe is a YAML file. The following is a an example Recipe YAML file that can be used as a starting point when building a new Recipe.

@@snip [template-recipe.yaml]($quine$/recipes/template-recipe.yaml)

Learn how to create a recipe in the @ref:[Recipe Tutorial](../getting-started/recipes-tutorial.md) or the @ref:[Recipe Reference](../reference/recipe-ref-manual.md) for information about Recipe document content.

## How to Contribute a Recipe

Recipes are contributed by creating a pull request (PR) to the @link:[Quine git repository](https://github.com/thatdot/quine). Recipes are stored in the directory @link:[`quine/recipes`](https://github.com/thatdot/quine/tree/main/quine/recipes).
