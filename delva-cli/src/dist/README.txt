delva — the command line for Delva stores

Requirements: Java 22 or newer, found through JAVA_HOME or on the PATH.

Install: unpack this archive anywhere and put its bin directory on the PATH, or run bin/delva (bin\delva.bat on
Windows) directly.

    delva --help                     every command, with examples
    delva init ./data/store          create a store
    export DELVA_STORE=./data/store  name it once for the commands after
    delva insert orders/*.json       insert documents; their ids are printed
    delva search kv status open -S   find documents
    delva get 42                     print one

JVM options, such as a larger heap for very large documents, go in DELVA_OPTS (or JAVA_OPTS):

    DELVA_OPTS=-Xmx4g delva insert catalog.json

Shell completion:

    bash        source <(delva completion bash)                               in ~/.bashrc
    zsh         delva completion zsh > "${fpath[1]}/_delva"
    fish        delva completion fish > ~/.config/fish/completions/delva.fish
    PowerShell  delva completion powershell | Out-String | Invoke-Expression  in $PROFILE

Documentation: https://github.com/aoreshkov/delva/tree/main/delva-cli
Issues: https://github.com/aoreshkov/delva/issues
Licence: Apache License 2.0 (LICENSE); third-party notices in NOTICE.
