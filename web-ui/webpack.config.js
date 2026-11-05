const path = require("path");

module.exports = {
  entry: {
    apacheCamel: path.resolve(__dirname, "src/index.jsx"),
  },
  output: {
    library: "[name]",
    libraryTarget: "umd",
    umdNamedDefine: true,
    filename: "[name].js",
    publicPath: "",
    path: path.resolve(__dirname, "build/generated-resources/mounted"),
  },
  module: {
    rules: [
      {
        test: /\.jsx?$/,
        exclude: /node_modules/,
        use: "babel-loader",
      },
      {
        test: /\.css$/,
        use: ["style-loader", "css-loader"],
      },
    ],
  },
  resolve: {
    extensions: [".js", ".jsx"],
  },
  externals: [
    "react",
    "react-dom",
    "react-hook-form",
    "@hookform/resolvers/yup",
    "@inductiveautomation/ignition-web-ui",
    "@inductiveautomation/ignition-gateway-lib",
  ],
};
