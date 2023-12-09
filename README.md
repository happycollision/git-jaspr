# `git jaspr` (Just Another Stacked Pull Request)

This is a reimplementation of [git spr](https://github.com/ejoffe/spr) written in Kotlin.

## TODO Add documentation 😜

## Features to add
  - Branch cleanup
    - An option to remove the branches for merged PRs
    - A command to interactively clean PRs that you opened (to handle orphaned ones)
  - Add a tracked Revision to the commit bodies when updating them. Then use this to warn if someone is attempting to jaspr update an older version on top of a newer one. This will typically only happen when collaborating w/others on the same stack
  - Add command to list all PR stacks you have opened
- Figure out if there's a way to automate publishing like git spr does
