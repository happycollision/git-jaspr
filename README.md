# Git Jaspr (Just Another Stacked Pull Request)

This is a reimplementation of [git spr](https://github.com/ejoffe/spr) written in Kotlin.

# Why

Because I like Gerrit, and wish GitHub were Gerrit. But since it isn't, I have to use this tool instead.
For more rationale see this [excellent blog post](https://jg.gg/2018/09/29/stacked-diffs-versus-pull-requests/).

# How

## Installing

Download the appropriate standalone binary for your platform (Linux or OS X, sorry Windows users, you can run the Java
version maybe?) and install it into your `PATH` somewhere (`~/.local/bin`?) and then create a configuration file in 
`~/.git-jaspr.properties` with the following contents:

```properties
github-token=<GH PAT>
```

Where `<GH PAT>` is a GitHub Personal Access Token (classic) with the permissions `read:org`, `read:user`, `repo`, and 
`user:email`.

## Using

Some commands to try:
```shell
$ git jaspr -h 
$ git jaspr status
$ git jaspr push
$ git jaspr merge
$ git jaspr auto-merge
$ git jaspr clean
```

Any of the above can be invoked with `--help` (except just `git jaspr` which requires `-h` for reasons not worth going
into).

Enjoy!
