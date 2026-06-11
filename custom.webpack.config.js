const {merge} = require('webpack-merge');
var generated = require('./scalajs.webpack.config');

var local = {
    resolve: {"fallback": {"crypto": false}},
    module: {
        rules: [
            {
                test: /\.css$/,
                use: ['style-loader', 'css-loader']
            },
            {
                test: /\.(ttf|eot|woff|png|glb|svg|md)$/,
                use: 'file-loader'
            },
            {
                test: /\.(eot)$/,
                use: 'url-loader'
            },
            {
                test: /\.js$/,
                enforce: "pre",
                use: [{
                    loader: "source-map-loader",
                    options: {
                        filterSourceMappingUrl: (url, resourcePath) => {
                            // Ignore source maps from dependencies that reference non-existent paths
                            if (resourcePath.includes('node_modules')) {
                                return false;
                            }
                            return true;
                        }
                    }
                }]
            }
        ]
    },
    ignoreWarnings: [
        // Ignore source map warnings from dependencies
        /Failed to parse source map/,
        /source map.*ENOENT/
    ]
};

module.exports = merge(generated, local);
