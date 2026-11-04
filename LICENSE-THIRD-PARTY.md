# Third-party components

## Bundled in the mod jar

**imgui-java** (https://github.com/SpaiR/imgui-java), MIT License, Copyright (c) 2020 SpaiR

**Dear ImGui** (https://github.com/ocornut/imgui), MIT License, Copyright (c) 2014-2023 Omar Cornut

Both are provided under the MIT License:

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
> documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
> rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
> persons to whom the Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
> Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
> WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
> COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
> OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Not bundled

FFmpeg is a separate program found on the user's system. Distant Horizons, Oculus, Embeddium and Simple Voice Chat are
optional mods the user installs; Flashback Reforged only calls into them when they are present.

## Relationship to Flashback

Flashback Reforged is an independent Forge implementation inspired by Flashback by Moulberry. Flashback's source is
"all rights reserved", so none of it is included here; it was read to understand behavior and which integration points
exist. The replay file format is Flashback Reforged's own and is not compatible with Flashback replays.
