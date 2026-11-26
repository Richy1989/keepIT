/** Images imported from the repository (the app icon in docs/icon): the bundler gives their URL. */
declare module '*.png' {
  const src: string;
  export default src;
}
