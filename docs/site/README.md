# HaramVeil — GitHub Pages Site

This folder (`docs/site/`) contains the static site published to GitHub Pages.

## Structure
```
docs/site/
├── index.html        # Landing page
└── assets/
    └── style.css     # Theme (dark, Islamic-inspired, responsive)
```

## Publish to GitHub Pages

In the repository **Settings → Pages**, set:
- **Source:** Deploy from a branch
- **Branch:** `main` (or `master`)
- **Folder:** `/docs`

GitHub Pages will serve `docs/site/index.html` at
`https://darkmaster0345.github.io/HaramVeil/`.

> The links in `index.html` point to the `docs/*.md` files and the GitHub
> repository. They use the `main` branch by default — adjust the branch name in
> the URLs if your default branch differs.

## Local preview
```bash
python3 -m http.server 8080 --directory docs/site
# open http://localhost:8080
```
