const fs = require('fs');
const path = require('path');

function search(dir) {
    try {
        const files = fs.readdirSync(dir);
        for (const file of files) {
            if (file === 'proc' || file === 'sys' || file === 'dev' || file === 'node_modules') continue;
            const fullPath = path.join(dir, file);
            if (file === 'image_2.png' || file === 'soldier3.png' || file === 'boss3.png' || file === 'arena3.png') {
                console.log('FOUND: ' + fullPath);
            }
            try {
                const stat = fs.statSync(fullPath);
                if (stat.isDirectory()) {
                    search(fullPath);
                }
            } catch (e) {}
        }
    } catch (e) {}
}

search('/'); // In node, '/' is the container root.
