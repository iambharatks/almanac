class Solution {
public:
   
    int minMoves(vector<string>& classroom, int energy) {
        queue<int> x,y,qmask,e,mv;
        int n = size(classroom);
        int m = size(classroom[0]);
        vector<vector<int>> id(n,vector<int>(m,false));
        int litter = 0;
        int dx[4] = {-1,0,0,1};
        int dy[4] = {0,-1,1,0};
        int mask = 0;
        for(int i = 0 ; i < n ; i++){
            for(int j = 0 ; j < m ; j++){
                if(classroom[i][j] == 'S'){ 
                    x.push(i);
                    y.push(j);
                    e.push(energy);
                    mv.push(0);
                    continue;
                }
                if(classroom[i][j] == 'L'){
                    id[i][j] = 1<<litter;
                    litter++;
                    mask |= id[i][j];
                }
            }
        }
        if(litter == 0) return 0;
        qmask.push(0);
        vector<vector<vector<int>>> best(n,vector<vector<int>>(m,vector<int>(1<<litter,-1)));
        best[x.front()][y.front()][0] = energy;
        while(!x.empty()){
            int i = x.front();
            int j = y.front();
            int en = e.front();
            int msk = qmask.front();
            int cur = mv.front();
            x.pop();
            qmask.pop();
            y.pop();
            e.pop();
            mv.pop();
            for(int k = 0 ; k < 4 ; k++){
                int ni = i+dx[k];
                int nj = j+dy[k];
                if(nj >= 0 && nj < m && ni >= 0 && ni < n && classroom[ni][nj] != 'X'){
                    if(en == 0) continue;
                    int nmask = msk| id[ni][nj];
                    if(classroom[ni][nj] == 'L'){
                        if(nmask == mask) return cur+1;
                    }
                    int en1 = (classroom[ni][nj] == 'R')?energy:en-1;
                    if(en1 > best[ni][nj][nmask]){
                        best[ni][nj][nmask] = en1;
                        x.push(ni);
                        y.push(nj);
                        mv.push(cur+1);
                        qmask.push(nmask);
                        e.push(en1);
                    }
                }
            }
        }
        return -1;
    }
};