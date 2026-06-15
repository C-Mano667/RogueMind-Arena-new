const fs = require('fs');
const path = require('path');

function search(dir) {
    let files = [];
    try {
        files = fs.readdirSync(dir);
    } catch (e) { return; }
    
    for (const file of files) {
        if (['proc', 'sys', 'dev', 'node_modules', '.git'].includes(file)) continue;
        const fullPath = path.join(dir, file);
        if (file.includes('image_2') || file.includes('soldier3') || file.includes('boss3') || file.includes('arena3')) {
            console.log('FOUND: ' + fullPath);
        }
        try {
            const stat = fs.statSync(fullPath);
            if (stat.isDirectory()) {
                search(fullPath);
            } else if (file.endsWith('.png')) {
                // console.log("png: " + fullPath);
            }
        } catch (e) {}
    }
}

search('/app/applet');
search('/mnt');
search('/');
